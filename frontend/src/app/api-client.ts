import { HttpClient } from '@angular/common/http';
import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { z } from 'zod';

import type {
  DownloadUrlResponse,
  EditorOpenResponse,
  EditorRowsResponse,
  ExportRepairResponse,
  MergeResponse,
  RepairOperation,
  RepairPreview,
  TrackGeoJson,
  UploadFileResult,
} from './models';

const MaybeNumber = z.number().nullish().transform((value) => value ?? undefined);
const MaybeString = z.string().nullish().transform((value) => value ?? undefined);

const FitLayoutSchema = z.object({
  counts: z.array(z.object({ type: z.string(), count: z.number() })),
  totalMessages: z.number(),
  totalFields: z.number(),
});

const RideSummarySchema = z.object({
  sport: MaybeString,
  totalDistanceM: MaybeNumber,
  elapsedSeconds: MaybeNumber,
  movingSeconds: MaybeNumber,
  avgSpeedMps: MaybeNumber,
  maxSpeedMps: MaybeNumber,
  avgPowerW: MaybeNumber,
  maxPowerW: MaybeNumber,
  avgTempC: MaybeNumber,
  maxTempC: MaybeNumber,
});

const DeviceInfoSchema = z.object({
  index: z.number(),
  manufacturer: z.string(),
  productName: MaybeString,
  product: MaybeNumber,
  kind: MaybeString,
  softwareVersion: MaybeNumber,
  serialNumber: MaybeNumber,
  batteryStatus: MaybeString,
  sourceType: MaybeString,
});

const UploadUrlResultSchema = z.object({
  id: z.string(),
  name: z.string(),
  url: z.string(),
  expiresAt: z.string(),
});

const UploadUrlsResponseSchema = z.object({
  files: z.array(UploadUrlResultSchema),
});

const UploadFileResultSchema = z.object({
  id: z.string(),
  fileId: z.object({
    manufacturer: MaybeNumber,
    product: MaybeNumber,
    serialNumber: MaybeNumber,
    timeCreated: MaybeString,
  }),
  summary: RideSummarySchema,
  devices: z.array(DeviceInfoSchema),
  layout: FitLayoutSchema,
});

const DescribeResponseSchema = z.object({
  files: z.array(UploadFileResultSchema),
});

const MergeReportSchema = z.object({
  segments: z.array(
    z.object({
      records: z.number(),
      start: z.string(),
      end: z.string(),
      distanceM: MaybeNumber,
    }),
  ),
  gaps: z.array(z.object({ afterSegment: z.number(), seconds: z.number() })),
  totalDistanceM: MaybeNumber,
  elapsedSeconds: MaybeNumber,
  movingSeconds: MaybeNumber,
  timerEventsAdded: z.number(),
  lapStrategy: z.string(),
  layout: FitLayoutSchema,
});

const MergeResponseSchema = z.object({
  id: MaybeString,
  report: MergeReportSchema,
});

const DownloadUrlResponseSchema = z.object({
  id: z.string(),
  url: z.string(),
  expiresAt: z.string(),
});

const TrackGeoJsonSchema = z.object({
  type: z.literal('FeatureCollection'),
  features: z.array(
    z.object({
      type: z.literal('Feature'),
      properties: z.record(z.string(), z.unknown()).optional(),
      geometry: z.object({
        type: z.union([z.literal('LineString'), z.literal('Point')]),
        coordinates: z.unknown(),
      }),
    }),
  ),
});

const EditorMessageGroupSchema = z.object({
  name: z.string(),
  count: z.number(),
  status: z.string(),
  issues: z.number(),
});

const EditorCellSchema = z.object({
  field: z.string(),
  value: z.string(),
  numeric: MaybeNumber,
});

const EditorRecordRowSchema = z.object({
  index: z.number(),
  messageIndex: z.number(),
  messageType: z.string(),
  timestamp: MaybeString,
  position: MaybeString,
  heartRate: MaybeNumber,
  power: MaybeNumber,
  speedMps: MaybeNumber,
  cadence: MaybeNumber,
  altitudeM: MaybeNumber,
  temperatureC: MaybeNumber,
  fields: z.array(EditorCellSchema),
  issueIds: z.array(z.string()),
});

const RepairOperationSchema = z.object({
  kind: z.string(),
  messageType: z.string(),
  startIndex: z.number(),
  endIndex: z.number(),
  field: MaybeString,
  value: MaybeNumber,
});

const DiagnosticIssueSchema = z.object({
  id: z.string(),
  kind: z.string(),
  severity: z.string(),
  title: z.string(),
  detail: z.string(),
  messageType: z.string(),
  startIndex: z.number(),
  endIndex: z.number(),
  field: MaybeString,
  suggestedOperations: z.array(RepairOperationSchema),
});

const EditorVerificationSchema = z.object({
  status: z.string(),
  canExport: z.boolean(),
  checks: z.array(z.string()),
});

const RepairChangeSchema = z.object({
  rowIndex: z.number(),
  field: z.string(),
  before: z.string(),
  after: z.string(),
  method: z.string(),
});

const RepairPreviewSchema = z.object({
  operations: z.array(RepairOperationSchema),
  changes: z.array(RepairChangeSchema),
  verification: EditorVerificationSchema,
});

const EditorRowsResponseSchema = z.object({
  messageType: z.string(),
  offset: z.number(),
  limit: z.number(),
  total: z.number(),
  rows: z.array(EditorRecordRowSchema),
});

const EditorOpenResponseSchema = z.object({
  id: z.string(),
  summary: RideSummarySchema,
  devices: z.array(DeviceInfoSchema),
  layout: FitLayoutSchema,
  anatomy: z.array(EditorMessageGroupSchema),
  diagnostics: z.array(DiagnosticIssueSchema),
  rows: EditorRowsResponseSchema,
  verification: EditorVerificationSchema,
});

const ExportRepairResponseSchema = z.object({
  id: z.string(),
  preview: RepairPreviewSchema,
});

@Injectable({ providedIn: 'root' })
export class FfmForgeApi {
  private readonly http = inject(HttpClient);
  private readonly prefix = '/ffmforge/v1';

  async uploadFiles(files: readonly File[]): Promise<readonly { id: string; name: string }[]> {
    const uploadResponse = UploadUrlsResponseSchema.parse(
      await firstValueFrom(
        this.http.post(`${this.prefix}/uploads`, {
          files: files.map((file) => file.name),
        }),
      ),
    );

    await Promise.all(
      uploadResponse.files.map(async (upload, index) => {
        const response = await fetch(upload.url, {
          method: 'PUT',
          body: files[index],
        }).catch((err: unknown) => {
          throw new Error(
            `Upload failed for ${upload.name} before S3 accepted it. If this is local dev, apply the S3 CORS update for http://127.0.0.1:4200. ${messageOf(err)}`,
          );
        });
        if (!response.ok) {
          throw new Error(`Upload failed for ${upload.name}: HTTP ${response.status}`);
        }
      }),
    );

    return uploadResponse.files.map((file) => ({ id: file.id, name: file.name }));
  }

  async describe(ids: readonly string[]): Promise<readonly UploadFileResult[]> {
    const response = DescribeResponseSchema.parse(
      await firstValueFrom(this.http.post(`${this.prefix}/fit/describe`, { ids })),
    );
    return response.files;
  }

  async merge(ids: readonly string[], dryRun: boolean, lapStrategy: string): Promise<MergeResponse> {
    return MergeResponseSchema.parse(
      await firstValueFrom(
        this.http.post(`${this.prefix}/fit/merge`, {
          ids,
          gapHandling: 'preserve',
          lapStrategy,
          dryRun,
        }),
      ),
    );
  }

  async download(id: string): Promise<DownloadUrlResponse> {
    return DownloadUrlResponseSchema.parse(
      await firstValueFrom(this.http.get(`${this.prefix}/fit/${encodeURIComponent(id)}/download`)),
    );
  }

  async track(id: string): Promise<TrackGeoJson> {
    return TrackGeoJsonSchema.parse(
      await firstValueFrom(this.http.get(`${this.prefix}/fit/${encodeURIComponent(id)}/track`)),
    );
  }

  async editorOpen(id: string): Promise<EditorOpenResponse> {
    return EditorOpenResponseSchema.parse(
      await firstValueFrom(this.http.post(`${this.prefix}/fit/editor/open`, { id })),
    );
  }

  async editorRows(id: string, messageType: string, offset: number, limit: number): Promise<EditorRowsResponse> {
    return EditorRowsResponseSchema.parse(
      await firstValueFrom(this.http.post(`${this.prefix}/fit/editor/rows`, { id, messageType, offset, limit })),
    );
  }

  async editorRepairPreview(id: string, operations: readonly RepairOperation[]): Promise<RepairPreview> {
    return RepairPreviewSchema.parse(
      await firstValueFrom(this.http.post(`${this.prefix}/fit/editor/repair-preview`, { id, operations })),
    );
  }

  async editorExport(id: string, operations: readonly RepairOperation[]): Promise<ExportRepairResponse> {
    return ExportRepairResponseSchema.parse(
      await firstValueFrom(this.http.post(`${this.prefix}/fit/editor/export`, { id, operations })),
    );
  }
}

export function messageOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const backendMessage = typeof err.error?.message === 'string' ? err.error.message : undefined;
    return backendMessage ?? `HTTP ${err.status} from ${err.url ?? 'the API'}`;
  }
  if (err instanceof Error) return err.message;
  return 'Unknown error';
}
