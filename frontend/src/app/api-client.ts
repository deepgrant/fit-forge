import { HttpClient } from '@angular/common/http';
import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { z } from 'zod';

import type {
  DownloadUrlResponse,
  MergeResponse,
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
}

export function messageOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const backendMessage = typeof err.error?.message === 'string' ? err.error.message : undefined;
    return backendMessage ?? `HTTP ${err.status} from ${err.url ?? 'the API'}`;
  }
  if (err instanceof Error) return err.message;
  return 'Unknown error';
}
