export type UploadState = 'queued' | 'uploading' | 'ready' | 'failed';

export interface SegmentFile {
  readonly localId: string;
  readonly file: File;
  readonly state: UploadState;
  readonly remoteId?: string;
  readonly error?: string;
}

export interface FileId {
  readonly manufacturer?: number;
  readonly product?: number;
  readonly serialNumber?: number;
  readonly timeCreated?: string;
}

export interface RideSummary {
  readonly sport?: string;
  readonly totalDistanceM?: number;
  readonly elapsedSeconds?: number;
  readonly movingSeconds?: number;
  readonly avgSpeedMps?: number;
  readonly maxSpeedMps?: number;
  readonly avgPowerW?: number;
  readonly maxPowerW?: number;
  readonly avgTempC?: number;
  readonly maxTempC?: number;
}

export interface DeviceInfo {
  readonly index: number;
  readonly manufacturer: string;
  readonly productName?: string;
  readonly product?: number;
  readonly kind?: string;
  readonly softwareVersion?: number;
  readonly serialNumber?: number;
  readonly batteryStatus?: string;
  readonly sourceType?: string;
}

export interface FitLayout {
  readonly counts: ReadonlyArray<{ readonly type: string; readonly count: number }>;
  readonly totalMessages: number;
  readonly totalFields: number;
}

export interface UploadFileResult {
  readonly id: string;
  readonly fileId: FileId;
  readonly summary: RideSummary;
  readonly devices: readonly DeviceInfo[];
  readonly layout: FitLayout;
}

export interface SegmentInfo {
  readonly records: number;
  readonly start: string;
  readonly end: string;
  readonly distanceM?: number;
}

export interface GapInfo {
  readonly afterSegment: number;
  readonly seconds: number;
}

export interface MergeReport {
  readonly segments: readonly SegmentInfo[];
  readonly gaps: readonly GapInfo[];
  readonly totalDistanceM?: number;
  readonly elapsedSeconds?: number;
  readonly movingSeconds?: number;
  readonly timerEventsAdded: number;
  readonly lapStrategy: string;
  readonly layout: FitLayout;
}

export interface MergeResponse {
  readonly id?: string;
  readonly report: MergeReport;
}

export interface DownloadUrlResponse {
  readonly id: string;
  readonly url: string;
  readonly expiresAt: string;
}

export type TrackGeometryType = 'LineString' | 'Point';

export interface TrackGeometry {
  readonly type: TrackGeometryType;
  readonly coordinates: unknown;
}

export interface TrackFeature {
  readonly type: 'Feature';
  readonly properties?: Readonly<Record<string, unknown>>;
  readonly geometry: TrackGeometry;
}

export interface TrackGeoJson {
  readonly type: 'FeatureCollection';
  readonly features: readonly TrackFeature[];
}

export interface RouteTrack {
  readonly id: string;
  readonly name: string;
  readonly color: string;
  readonly geojson: TrackGeoJson;
}
