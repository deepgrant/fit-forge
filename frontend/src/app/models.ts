export type UploadState = 'queued' | 'uploading' | 'ready' | 'failed';
export type DownloadFormat = 'fit' | 'gpx';

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
  readonly format: DownloadFormat;
  readonly filename: string;
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

export interface EditorMessageGroup {
  readonly name: string;
  readonly count: number;
  readonly status: string;
  readonly issues: number;
}

export interface EditorCell {
  readonly field: string;
  readonly value: string;
  readonly numeric?: number;
}

export interface EditorRecordRow {
  readonly index: number;
  readonly messageIndex: number;
  readonly messageType: string;
  readonly timestamp?: string;
  readonly position?: string;
  readonly heartRate?: number;
  readonly power?: number;
  readonly speedMps?: number;
  readonly cadence?: number;
  readonly altitudeM?: number;
  readonly temperatureC?: number;
  readonly fields: readonly EditorCell[];
  readonly issueIds: readonly string[];
}

export interface RepairOperation {
  readonly kind: string;
  readonly messageType: string;
  readonly startIndex: number;
  readonly endIndex: number;
  readonly field?: string;
  readonly value?: number;
}

export interface DiagnosticIssue {
  readonly id: string;
  readonly kind: string;
  readonly severity: string;
  readonly title: string;
  readonly detail: string;
  readonly messageType: string;
  readonly startIndex: number;
  readonly endIndex: number;
  readonly field?: string;
  readonly suggestedOperations: readonly RepairOperation[];
}

export interface EditorVerification {
  readonly status: string;
  readonly canExport: boolean;
  readonly checks: readonly string[];
}

export interface RepairChange {
  readonly rowIndex: number;
  readonly field: string;
  readonly before: string;
  readonly after: string;
  readonly method: string;
}

export interface RepairPreview {
  readonly operations: readonly RepairOperation[];
  readonly changes: readonly RepairChange[];
  readonly verification: EditorVerification;
}

export interface EditorRowsResponse {
  readonly messageType: string;
  readonly offset: number;
  readonly limit: number;
  readonly total: number;
  readonly rows: readonly EditorRecordRow[];
}

export interface EditorOpenResponse {
  readonly id: string;
  readonly summary: RideSummary;
  readonly devices: readonly DeviceInfo[];
  readonly layout: FitLayout;
  readonly anatomy: readonly EditorMessageGroup[];
  readonly diagnostics: readonly DiagnosticIssue[];
  readonly rows: EditorRowsResponse;
  readonly verification: EditorVerification;
}

export interface ExportRepairResponse {
  readonly id: string;
  readonly preview: RepairPreview;
}
