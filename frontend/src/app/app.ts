import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, computed, effect, inject, signal } from '@angular/core';

import { FfmForgeApi, messageOf } from './api-client';
import { distance, duration, fileSize, power, speed, temp, timeRange } from './format';
import type {
  DeviceInfo,
  DiagnosticIssue,
  EditorOpenResponse,
  EditorRecordRow,
  EditorRowsResponse,
  ExportRepairResponse,
  MergeResponse,
  RepairOperation,
  RepairPreview,
  RouteTrack,
  SegmentFile,
  TrackFeature,
  TrackGeoJson,
  UploadFileResult,
} from './models';

type Theme = 'light' | 'dark';
type LapStrategy = 'OnePerSegment' | 'KeepOriginal';
type WorkspaceView = 'merge' | 'editor';
type EditorRowsDirection = 'previous' | 'next';

const RouteColors = ['#ff6a1a', '#1f9d6b', '#2f80ed', '#e0453c', '#8b5cf6', '#e0921a', '#008ea8', '#c026d3'];
const OpenFreeMapStyleUrl = 'https://tiles.openfreemap.org/styles/liberty';
const MapLibreCssUrl = 'https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css';
const MapLibreScriptUrl = 'https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js';
const EditorRowsEdgePx = 36;

interface GeoJsonSource {
  setData(data: unknown): void;
}

interface MapBounds {
  extend(position: [number, number]): void;
  isEmpty(): boolean;
}

interface MapLibreMap {
  addControl(control: unknown, position?: string): void;
  addLayer(layer: Readonly<Record<string, unknown>>): void;
  addSource(id: string, source: Readonly<Record<string, unknown>>): void;
  fitBounds(bounds: MapBounds, options: Readonly<Record<string, unknown>>): void;
  getLayer(id: string): unknown;
  getSource(id: string): GeoJsonSource | undefined;
  on(type: 'load', listener: () => void): void;
  remove(): void;
  removeLayer(id: string): void;
  removeSource(id: string): void;
  resize(): void;
}

interface MapLibreGlobal {
  LngLatBounds: new () => MapBounds;
  Map: new (options: Readonly<Record<string, unknown>>) => MapLibreMap;
  NavigationControl: new (options: Readonly<Record<string, unknown>>) => unknown;
}

interface DisplayDevice {
  readonly key: string;
  readonly manufacturer: string;
  readonly logoSrc?: string;
  readonly logoAlt: string;
  readonly logoClass?: string;
  readonly markText: string;
  readonly name: string;
  readonly typeLabel: string;
  readonly sourceLabel?: string;
  readonly statusLabel?: string;
  readonly idLabel: string;
  readonly recordingCount: number;
  readonly occurrenceCount: number;
}

declare const maplibregl: MapLibreGlobal;

declare global {
  interface Window {
    maplibregl?: MapLibreGlobal;
  }
}

@Component({
  selector: 'ffm-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements AfterViewInit, OnDestroy {
  private static mapLibreLoad?: Promise<void>;

  private readonly api = inject(FfmForgeApi);
  private map?: MapLibreMap;
  private mapLoaded = false;
  private renderedRouteIds = new Set<string>();
  private editorRowsLoadInFlight = false;

  @ViewChild('mapHost') private mapHost?: ElementRef<HTMLDivElement>;

  protected readonly theme = signal<Theme>('light');
  protected readonly activeView = signal<WorkspaceView>('merge');
  protected readonly segments = signal<readonly SegmentFile[]>([]);
  protected readonly descriptions = signal<readonly UploadFileResult[]>([]);
  protected readonly routeTracks = signal<readonly RouteTrack[]>([]);
  protected readonly dryRun = signal<MergeResponse | null>(null);
  protected readonly merged = signal<MergeResponse | null>(null);
  protected readonly lapStrategy = signal<LapStrategy>('OnePerSegment');
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly editorFile = signal<SegmentFile | null>(null);
  protected readonly editorOpen = signal<EditorOpenResponse | null>(null);
  protected readonly editorRows = signal<EditorRowsResponse | null>(null);
  protected readonly editorRowsBusy = signal<EditorRowsDirection | null>(null);
  protected readonly editorMessageType = signal('record');
  protected readonly editorSelectedIssueId = signal<string | null>(null);
  protected readonly editorOperations = signal<readonly RepairOperation[]>([]);
  protected readonly editorPreview = signal<RepairPreview | null>(null);
  protected readonly editorExport = signal<ExportRepairResponse | null>(null);

  protected readonly readyIds = computed(() =>
    this.segments()
      .map((segment) => segment.remoteId)
      .filter((id): id is string => id !== undefined),
  );
  protected readonly canMerge = computed(() => this.readyIds().length >= 2 && this.busy() === null);
  protected readonly primaryActivity = computed(() => this.descriptions().at(0));
  protected readonly report = computed(() => this.dryRun()?.report ?? this.merged()?.report);
  protected readonly displayDevices = computed(() => this.groupDevices(this.descriptions()));
  protected readonly totalRecords = computed(() => {
    const report = this.report();
    return report?.segments.reduce((sum, segment) => sum + segment.records, 0) ?? this.descriptions().reduce((sum, file) => sum + file.layout.totalMessages, 0);
  });
  protected readonly gapSeconds = computed(() => this.report()?.gaps.reduce((sum, gap) => sum + gap.seconds, 0) ?? 0);
  protected readonly gapWeight = computed(() => Math.max(1, this.gapSeconds()));
  protected readonly displayDistanceM = computed(() => this.report()?.totalDistanceM ?? this.primaryActivity()?.summary.totalDistanceM);
  protected readonly displayMovingSeconds = computed(() => this.report()?.movingSeconds ?? this.primaryActivity()?.summary.movingSeconds);
  protected readonly displayElapsedSeconds = computed(() => this.report()?.elapsedSeconds ?? this.primaryActivity()?.summary.elapsedSeconds);
  protected readonly editorIssues = computed(() => this.editorOpen()?.diagnostics ?? []);
  protected readonly editorSelectedIssue = computed(() => {
    const issues = this.editorIssues();
    const selected = this.editorSelectedIssueId();
    return issues.find((issue) => issue.id === selected) ?? issues.at(0);
  });
  protected readonly editorDevices = computed(() => this.editorOpen()?.devices.map((device) => this.displayDeviceOf(device, this.editorOpen()?.id ?? 'editor')) ?? []);
  protected readonly canPreviewRepair = computed(() => this.editorOpen() !== null && this.editorOperations().length > 0 && this.busy() === null);
  protected readonly canExportRepair = computed(() => this.canPreviewRepair() && (this.editorPreview()?.verification.canExport ?? this.editorOpen()?.verification.canExport ?? false));

  protected readonly distance = distance;
  protected readonly duration = duration;
  protected readonly fileSize = fileSize;
  protected readonly power = power;
  protected readonly speed = speed;
  protected readonly temp = temp;
  protected readonly timeRange = timeRange;

  constructor() {
    const stored = window.localStorage.getItem('ffmforge-theme');
    this.setTheme(stored === 'dark' ? 'dark' : 'light');
    effect(() => {
      const tracks = this.routeTracks();
      queueMicrotask(() => this.renderRouteTracks(tracks));
    });
  }

  ngAfterViewInit(): void {
    void this.initializeMap();
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  private async initializeMap(): Promise<void> {
    if (!this.mapHost) return;

    try {
      await this.loadMapLibre();
    } catch (err) {
      this.error.set(messageOf(err));
      return;
    }

    this.map = new maplibregl.Map({
      container: this.mapHost.nativeElement,
      style: OpenFreeMapStyleUrl,
      center: [-98.5795, 39.8283],
      zoom: 3,
      attributionControl: { compact: true },
    });
    this.map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    this.map.on('load', () => {
      this.mapLoaded = true;
      this.renderRouteTracks(this.routeTracks());
    });
  }

  protected async onFilesSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    await this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  protected async onDrop(event: DragEvent): Promise<void> {
    event.preventDefault();
    await this.addFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  protected allowDrop(event: DragEvent): void {
    event.preventDefault();
  }

  protected setLapStrategy(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.lapStrategy.set(value === 'KeepOriginal' ? 'KeepOriginal' : 'OnePerSegment');
    this.dryRun.set(null);
    this.merged.set(null);
  }

  protected toggleTheme(): void {
    this.setTheme(this.theme() === 'dark' ? 'light' : 'dark');
  }

  protected setActiveView(view: WorkspaceView): void {
    this.activeView.set(view);
    queueMicrotask(() => this.map?.resize());
  }

  protected removeSegment(localId: string): void {
    const removed = this.segments().find((segment) => segment.localId === localId);
    this.segments.update((segments) => segments.filter((segment) => segment.localId !== localId));
    if (removed?.remoteId) {
      this.descriptions.update((files) => files.filter((file) => file.id !== removed.remoteId));
      this.routeTracks.update((tracks) => tracks.filter((track) => track.id !== removed.remoteId));
    }
    this.dryRun.set(null);
    this.merged.set(null);
  }

  protected clearWorkspace(): void {
    this.segments.set([]);
    this.descriptions.set([]);
    this.routeTracks.set([]);
    this.dryRun.set(null);
    this.merged.set(null);
    this.error.set(null);
  }

  protected async dryRunMerge(): Promise<void> {
    if (!this.canMerge()) return;
    this.busy.set('Building merge preview');
    this.error.set(null);
    try {
      this.dryRun.set(await this.api.merge(this.readyIds(), true, this.lapStrategy()));
      this.merged.set(null);
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(null);
    }
  }

  protected async mergeAndDownload(): Promise<void> {
    if (!this.canMerge()) return;
    this.busy.set('Merging and preparing download');
    this.error.set(null);
    try {
      const merged = await this.api.merge(this.readyIds(), false, this.lapStrategy());
      this.merged.set(merged);
      this.dryRun.set(merged);
      if (merged.id) {
        const download = await this.api.download(merged.id);
        window.location.assign(download.url);
      }
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(null);
    }
  }

  protected async onEditorFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    await this.addEditorFile(Array.from(input.files ?? []).at(0));
    input.value = '';
  }

  protected async onEditorDrop(event: DragEvent): Promise<void> {
    event.preventDefault();
    await this.addEditorFile(Array.from(event.dataTransfer?.files ?? []).at(0));
  }

  protected async selectEditorMessageType(messageType: string): Promise<void> {
    const open = this.editorOpen();
    if (!open || messageType === this.editorMessageType()) return;

    this.busy.set(`Loading ${messageType} rows`);
    this.error.set(null);
    try {
      this.editorMessageType.set(messageType);
      this.editorRows.set(await this.api.editorRows(open.id, messageType, 0, 80));
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(null);
    }
  }

  protected onEditorTableScroll(event: Event): void {
    const table = event.currentTarget as HTMLElement;
    this.loadRowsAtTableEdge(table);
  }

  protected onEditorTableWheel(event: WheelEvent): void {
    const table = event.currentTarget as HTMLElement;
    if (event.deltaY > 0 && this.isTableAtBottom(table)) {
      void this.loadEditorRows('next', table);
    } else if (event.deltaY < 0 && this.isTableAtTop(table)) {
      void this.loadEditorRows('previous', table);
    }
  }

  private loadRowsAtTableEdge(table: HTMLElement): void {
    if (this.isTableAtBottom(table)) {
      void this.loadEditorRows('next', table);
    } else if (this.isTableAtTop(table)) {
      void this.loadEditorRows('previous', table);
    }
  }

  private async loadEditorRows(direction: EditorRowsDirection, table?: HTMLElement): Promise<void> {
    const open = this.editorOpen();
    const rows = this.editorRows();
    if (!open || !rows) return;

    const request =
      direction === 'next'
        ? this.nextEditorRowsRequest(rows)
        : this.previousEditorRowsRequest(rows);
    if (!request || this.editorRowsLoadInFlight) return;

    const beforeScrollHeight = table?.scrollHeight ?? 0;
    this.editorRowsLoadInFlight = true;
    this.editorRowsBusy.set(direction);
    this.error.set(null);
    try {
      const page = await this.api.editorRows(open.id, rows.messageType, request.offset, request.limit);
      this.editorRows.update((current) => (current ? this.mergeEditorRows(current, page, direction) : page));
      if (direction === 'previous' && table) {
        requestAnimationFrame(() => {
          table.scrollTop += table.scrollHeight - beforeScrollHeight;
        });
      }
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.editorRowsBusy.set(null);
      this.editorRowsLoadInFlight = false;
    }
  }

  private nextEditorRowsRequest(rows: EditorRowsResponse): { offset: number; limit: number } | undefined {
    const offset = rows.offset + rows.rows.length;
    if (offset >= rows.total) return undefined;
    return { offset, limit: rows.limit };
  }

  private previousEditorRowsRequest(rows: EditorRowsResponse): { offset: number; limit: number } | undefined {
    if (rows.offset === 0) return undefined;
    const offset = Math.max(0, rows.offset - rows.limit);
    return { offset, limit: rows.offset - offset };
  }

  private mergeEditorRows(
    current: EditorRowsResponse,
    page: EditorRowsResponse,
    direction: EditorRowsDirection,
  ): EditorRowsResponse {
    if (current.messageType !== page.messageType) return page;
    const currentIndexes = new Set(current.rows.map((row) => row.index));
    const pageIndexes = new Set(page.rows.map((row) => row.index));
    const mergedRows =
      direction === 'next'
        ? [...current.rows, ...page.rows.filter((row) => !currentIndexes.has(row.index))]
        : [...page.rows, ...current.rows.filter((row) => !pageIndexes.has(row.index))];
    return {
      ...current,
      offset: Math.min(current.offset, page.offset),
      limit: page.limit,
      total: page.total,
      rows: mergedRows,
    };
  }

  private isTableAtTop(table: HTMLElement): boolean {
    return table.scrollTop <= EditorRowsEdgePx;
  }

  private isTableAtBottom(table: HTMLElement): boolean {
    return table.scrollTop + table.clientHeight >= table.scrollHeight - EditorRowsEdgePx;
  }

  protected selectEditorIssue(issue: DiagnosticIssue): void {
    this.editorSelectedIssueId.set(issue.id);
  }

  protected stageSuggestedRepair(issue: DiagnosticIssue | undefined): void {
    const operation = issue?.suggestedOperations.at(0);
    if (!operation) return;
    this.editorOperations.update((operations) => [...operations, operation]);
    this.editorPreview.set(null);
    this.editorExport.set(null);
  }

  protected clearEditorRepairs(): void {
    this.editorOperations.set([]);
    this.editorPreview.set(null);
    this.editorExport.set(null);
  }

  protected async previewEditorRepairs(): Promise<void> {
    const open = this.editorOpen();
    if (!open || this.editorOperations().length === 0) return;
    this.busy.set('Previewing repairs');
    this.error.set(null);
    try {
      this.editorPreview.set(await this.api.editorRepairPreview(open.id, this.editorOperations()));
      this.editorExport.set(null);
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(null);
    }
  }

  protected async exportEditorRepairs(): Promise<void> {
    const open = this.editorOpen();
    if (!open || this.editorOperations().length === 0) return;
    this.busy.set('Saving repaired FIT file');
    this.error.set(null);
    try {
      const exported = await this.api.editorExport(open.id, this.editorOperations());
      this.editorExport.set(exported);
      this.editorPreview.set(exported.preview);
      const download = await this.api.download(exported.id);
      window.location.assign(download.url);
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(null);
    }
  }

  private async addFiles(files: readonly File[]): Promise<void> {
    const fitFiles = files.filter((file) => file.name.toLowerCase().endsWith('.fit'));
    if (fitFiles.length === 0) {
      this.error.set('Choose one or more .fit files.');
      return;
    }

    const additions = fitFiles.map((file) => ({
      localId: crypto.randomUUID(),
      file,
      state: 'uploading' as const,
    }));
    this.segments.update((segments) => [...segments, ...additions]);
    this.busy.set('Uploading FIT files');
    this.error.set(null);

    try {
      const uploaded = await this.api.uploadFiles(fitFiles);
      this.segments.update((segments) =>
        segments.map((segment) => {
          const index = additions.findIndex((addition) => addition.localId === segment.localId);
          if (index === -1) return segment;
          return { ...segment, state: 'ready', remoteId: uploaded[index].id };
        }),
      );
      await this.describeReadyFiles();
    } catch (err) {
      const message = messageOf(err);
      this.segments.update((segments) =>
        segments.map((segment) =>
          additions.some((addition) => addition.localId === segment.localId) ? { ...segment, state: 'failed', error: message } : segment,
        ),
      );
      this.error.set(message);
    } finally {
      this.busy.set(null);
    }
  }

  private async addEditorFile(file: File | undefined): Promise<void> {
    if (!file || !file.name.toLowerCase().endsWith('.fit')) {
      this.error.set('Choose one .fit file for the editor.');
      return;
    }

    const local: SegmentFile = {
      localId: crypto.randomUUID(),
      file,
      state: 'uploading',
    };
    this.editorFile.set(local);
    this.editorOpen.set(null);
    this.editorRows.set(null);
    this.editorMessageType.set('record');
    this.editorSelectedIssueId.set(null);
    this.editorOperations.set([]);
    this.editorPreview.set(null);
    this.editorExport.set(null);
    this.busy.set('Uploading FIT file for editor');
    this.error.set(null);

    try {
      const uploaded = (await this.api.uploadFiles([file])).at(0);
      if (!uploaded) throw new Error('Upload did not return a file id.');
      this.editorFile.set({ ...local, state: 'ready', remoteId: uploaded.id });
      const opened = await this.api.editorOpen(uploaded.id);
      this.editorOpen.set(opened);
      this.editorRows.set(opened.rows);
      this.editorSelectedIssueId.set(opened.diagnostics.at(0)?.id ?? null);
    } catch (err) {
      const message = messageOf(err);
      this.editorFile.set({ ...local, state: 'failed', error: message });
      this.error.set(message);
    } finally {
      this.busy.set(null);
    }
  }

  private async describeReadyFiles(): Promise<void> {
    const ids = this.readyIds();
    if (ids.length === 0) {
      this.descriptions.set([]);
      return;
    }
    this.descriptions.set(await this.api.describe(ids));
    this.routeTracks.set(await this.loadRouteTracks());
    this.dryRun.set(null);
    this.merged.set(null);
  }

  private async loadRouteTracks(): Promise<readonly RouteTrack[]> {
    const readySegments = this.segments().filter((segment) => segment.remoteId !== undefined);
    return Promise.all(
      readySegments.map(async (segment, index) => ({
        id: segment.remoteId!,
        name: segment.file.name,
        color: RouteColors[index % RouteColors.length],
        geojson: await this.api.track(segment.remoteId!),
      })),
    );
  }

  private setTheme(theme: Theme): void {
    this.theme.set(theme);
    document.documentElement.dataset['theme'] = theme;
    window.localStorage.setItem('ffmforge-theme', theme);
  }

  private groupDevices(files: readonly UploadFileResult[]): readonly DisplayDevice[] {
    const grouped = new Map<
      string,
      {
        device: DeviceInfo;
        readonly recordingIds: Set<string>;
        occurrenceCount: number;
      }
    >();

    for (const file of files) {
      for (const device of file.devices) {
        const key = this.deviceKey(device);
        const existing = grouped.get(key);
        if (existing) {
          existing.device = this.preferredDevice(existing.device, device);
          existing.recordingIds.add(file.id);
          existing.occurrenceCount += 1;
        } else {
          grouped.set(key, {
            device,
            recordingIds: new Set([file.id]),
            occurrenceCount: 1,
          });
        }
      }
    }

    return Array.from(grouped.entries())
      .map(([key, value]) => ({
        key,
        manufacturer: value.device.manufacturer,
        logoSrc: this.deviceLogoSrc(value.device),
        logoAlt: `${value.device.manufacturer} logo`,
        logoClass: this.deviceLogoClass(value.device),
        markText: this.deviceMarkText(value.device.manufacturer),
        name: this.deviceName(value.device),
        typeLabel: this.deviceTypeLabel(value.device),
        sourceLabel: this.deviceSourceLabel(value.device.sourceType),
        statusLabel: this.titleize(value.device.batteryStatus),
        idLabel: this.deviceIdLabel(value.device),
        recordingCount: value.recordingIds.size,
        occurrenceCount: value.occurrenceCount,
      }))
      .sort((a, b) => `${a.typeLabel}:${a.name}:${a.sourceLabel ?? ''}`.localeCompare(`${b.typeLabel}:${b.name}:${b.sourceLabel ?? ''}`));
  }

  private displayDeviceOf(device: DeviceInfo, recordingId: string): DisplayDevice {
    return {
      key: `${recordingId}:${this.deviceKey(device)}`,
      manufacturer: device.manufacturer,
      logoSrc: this.deviceLogoSrc(device),
      logoAlt: `${device.manufacturer} logo`,
      logoClass: this.deviceLogoClass(device),
      markText: this.deviceMarkText(device.manufacturer),
      name: this.deviceName(device),
      typeLabel: this.deviceTypeLabel(device),
      sourceLabel: this.deviceSourceLabel(device.sourceType),
      statusLabel: this.titleize(device.batteryStatus),
      idLabel: this.deviceIdLabel(device),
      recordingCount: 1,
      occurrenceCount: 1,
    };
  }

  protected issueSeverityLabel(issue: DiagnosticIssue): string {
    return issue.severity === 'error' ? 'repair needed' : 'warning';
  }

  protected operationLabel(operation: RepairOperation): string {
    const field = operation.field ? ` ${this.titleize(operation.field) ?? operation.field}` : '';
    const range = operation.startIndex === operation.endIndex ? `row ${operation.startIndex}` : `rows ${operation.startIndex}-${operation.endIndex}`;
    return `${this.titleize(operation.kind) ?? operation.kind}${field} · ${range}`;
  }

  protected cellValue(value: string | number | undefined): string {
    if (value === undefined) return '-';
    if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(2);
    return value;
  }

  protected editorFieldColumns(rows: EditorRowsResponse): readonly string[] {
    const columns = new Set<string>();
    rows.rows.forEach((row) => row.fields.forEach((field) => columns.add(field.field)));
    return Array.from(columns);
  }

  protected editorGenericGridColumns(rows: EditorRowsResponse): string {
    return `54px 190px repeat(${Math.max(1, this.editorFieldColumns(rows).length)}, minmax(120px, 1fr))`;
  }

  protected editorFieldValue(row: EditorRecordRow, column: string): string {
    return row.fields.find((field) => field.field === column)?.value ?? '-';
  }

  private deviceKey(device: DeviceInfo): string {
    if (device.serialNumber !== undefined) return `serial:${device.serialNumber}`;
    if (device.product !== undefined) {
      return ['product', device.manufacturer, device.product, device.kind ?? 'device', device.sourceType ?? ''].join(':').toLowerCase();
    }
    return ['label', this.deviceName(device), device.kind ?? 'device', device.sourceType ?? ''].join(':').toLowerCase();
  }

  private preferredDevice(a: DeviceInfo, b: DeviceInfo): DeviceInfo {
    return this.deviceScore(b) > this.deviceScore(a) ? b : a;
  }

  private deviceScore(device: DeviceInfo): number {
    return (
      (device.productName ? 8 : 0) +
      (device.serialNumber !== undefined ? 4 : 0) +
      (device.product !== undefined ? 2 : 0) +
      (device.batteryStatus ? 1 : 0)
    );
  }

  private deviceName(device: DeviceInfo): string {
    return device.productName ?? `${device.manufacturer} ${this.deviceTypeLabel(device).toLowerCase()}`;
  }

  private deviceTypeLabel(device: DeviceInfo): string {
    const accessoryType = this.garminAccessoryTypeLabel(device);
    if (accessoryType !== undefined) return accessoryType;

    const kind = device.kind ?? 'device';
    if (kind === 'device' && device.sourceType === 'local') return 'Recording device';
    if (kind === 'device') return 'Device';
    return this.titleize(kind) ?? kind;
  }

  private garminAccessoryTypeLabel(device: DeviceInfo): string | undefined {
    if (!this.isManufacturer(device, 'garmin')) return undefined;

    const productName = device.productName?.toLowerCase() ?? '';
    if (device.product === 4470 || productName.includes('varia vue')) return 'Headlight camera';
    if (device.product === 3808 || productName.includes('varia rct')) return 'Radar camera';
    if (productName.includes('varia radar')) return 'Radar';
    if (productName.includes('varia ut') || productName.includes('varia headlight')) return 'Headlight';
    if (productName.includes('varia taillight')) return 'Tail light';
    return undefined;
  }

  private deviceSourceLabel(sourceType: string | undefined): string | undefined {
    if (sourceType === undefined) return undefined;
    if (sourceType.toLowerCase() === 'antplus') return 'ANT+';
    if (sourceType.toLowerCase() === 'local') return 'Local';
    return this.titleize(sourceType);
  }

  private deviceIdLabel(device: DeviceInfo): string {
    const parts = [`FIT index ${device.index}`];
    if (device.serialNumber !== undefined) parts.push(`serial ${device.serialNumber}`);
    if (device.product !== undefined) parts.push(`product ${device.product}`);
    if (device.softwareVersion !== undefined) parts.push(`software ${device.softwareVersion}`);
    return parts.join(' / ');
  }

  private deviceMarkText(manufacturer: string): string {
    const normalized = this.normalizeManufacturer(manufacturer);
    if (normalized === 'garmin') return 'GARMIN';
    if (normalized === 'polar') return 'POLAR';
    if (normalized === 'wahoo' || normalized === 'wahoo fitness') return 'WAHOO';
    if (normalized === 'shimano') return 'SHIMANO';
    if (normalized === 'sram') return 'SRAM';
    return manufacturer
      .split(/\s+/)
      .filter(Boolean)
      .map((part) => part.at(0))
      .join('')
      .slice(0, 2)
      .toUpperCase();
  }

  private deviceLogoSrc(device: DeviceInfo): string | undefined {
    const normalized = this.normalizeManufacturer(device.manufacturer);
    if (normalized === 'garmin') return 'brands/garmin.svg';
    if (normalized === 'polar') return 'brands/polar.svg';
    if (normalized === 'wahoo' || normalized === 'wahoo fitness') return 'brands/wahoo.png';
    if (normalized === 'shimano') return 'brands/shimano.svg';
    if (normalized === 'sram') return 'brands/sram.svg';
    return undefined;
  }

  private deviceLogoClass(device: DeviceInfo): string | undefined {
    const normalized = this.normalizeManufacturer(device.manufacturer);
    if (normalized === 'garmin') return 'garmin-logo';
    if (normalized === 'shimano') return 'shimano-logo';
    if (normalized === 'sram') return 'sram-logo';
    return undefined;
  }

  private isManufacturer(device: DeviceInfo, manufacturer: string): boolean {
    return this.normalizeManufacturer(device.manufacturer) === manufacturer;
  }

  private normalizeManufacturer(manufacturer: string): string {
    return manufacturer.trim().toLowerCase();
  }

  private titleize(value: string | undefined): string | undefined {
    if (value === undefined) return undefined;
    return value
      .split(/[_\s-]+/)
      .filter(Boolean)
      .map((part) => part.at(0)!.toUpperCase() + part.slice(1).toLowerCase())
      .join(' ');
  }

  private loadMapLibre(): Promise<void> {
    if (window.maplibregl) return Promise.resolve();

    App.mapLibreLoad ??= new Promise<void>((resolve, reject) => {
      if (!document.getElementById('maplibre-gl-css')) {
        const link = document.createElement('link');
        link.id = 'maplibre-gl-css';
        link.rel = 'stylesheet';
        link.href = MapLibreCssUrl;
        document.head.append(link);
      }

      const existing = document.getElementById('maplibre-gl-js') as HTMLScriptElement | null;
      if (existing) {
        existing.addEventListener('load', () => resolve(), { once: true });
        existing.addEventListener('error', () => reject(new Error('Could not load MapLibre GL JS 5.24.0.')), { once: true });
        return;
      }

      const script = document.createElement('script');
      script.id = 'maplibre-gl-js';
      script.src = MapLibreScriptUrl;
      script.async = true;
      script.addEventListener('load', () => resolve(), { once: true });
      script.addEventListener('error', () => reject(new Error('Could not load MapLibre GL JS 5.24.0.')), { once: true });
      document.head.append(script);
    });

    return App.mapLibreLoad;
  }

  private renderRouteTracks(tracks: readonly RouteTrack[]): void {
    const map = this.map;
    if (!map || !this.mapLoaded) return;

    const nextIds = new Set(tracks.map((track) => track.id));
    for (const id of this.renderedRouteIds) {
      if (!nextIds.has(id)) {
        this.removeRouteLayers(id);
      }
    }

    for (const track of tracks) {
      this.upsertRouteTrack(track);
    }
    this.renderedRouteIds = nextIds;
    this.fitRouteBounds(tracks.map((track) => track.geojson));
    map.resize();
  }

  private upsertRouteTrack(track: RouteTrack): void {
    const map = this.map;
    if (!map) return;

    const sourceId = this.routeSourceId(track.id);
    const source = map.getSource(sourceId);
    if (source) {
      source.setData(track.geojson);
    } else {
      map.addSource(sourceId, {
        type: 'geojson',
        data: track.geojson,
      });
    }

    const lineLayerId = this.routeLineLayerId(track.id);
    if (!map.getLayer(lineLayerId)) {
      map.addLayer({
        id: lineLayerId,
        type: 'line',
        source: sourceId,
        filter: ['==', ['get', 'type'], 'track'],
        paint: {
          'line-color': track.color,
          'line-opacity': 0.92,
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 3, 12, 6, 16, 9],
        },
      });
    }

    const pointLayerId = this.routePointLayerId(track.id);
    if (!map.getLayer(pointLayerId)) {
      map.addLayer({
        id: pointLayerId,
        type: 'circle',
        source: sourceId,
        filter: ['!=', ['get', 'type'], 'track'],
        paint: {
          'circle-color': ['match', ['get', 'type'], 'start', '#1f9d6b', 'finish', '#e0453c', '#e0921a'],
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 7, 4, 13, 7],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 2,
        },
      });
    }
  }

  private removeRouteLayers(id: string): void {
    const map = this.map;
    if (!map) return;

    const pointLayerId = this.routePointLayerId(id);
    if (map.getLayer(pointLayerId)) map.removeLayer(pointLayerId);

    const lineLayerId = this.routeLineLayerId(id);
    if (map.getLayer(lineLayerId)) map.removeLayer(lineLayerId);

    const sourceId = this.routeSourceId(id);
    if (map.getSource(sourceId)) map.removeSource(sourceId);
  }

  private fitRouteBounds(files: readonly TrackGeoJson[]): void {
    const map = this.map;
    if (!map) return;

    const bounds = new maplibregl.LngLatBounds();
    for (const file of files) {
      for (const feature of file.features) {
        this.extendBounds(bounds, feature);
      }
    }
    if (!bounds.isEmpty()) {
      map.fitBounds(bounds, { padding: 42, maxZoom: 14, duration: 500 });
    }
  }

  private extendBounds(bounds: MapBounds, feature: TrackFeature): void {
    if (feature.geometry.type === 'Point') {
      const position = this.positionOf(feature.geometry.coordinates);
      if (position) bounds.extend(position);
      return;
    }

    if (feature.geometry.type === 'LineString' && Array.isArray(feature.geometry.coordinates)) {
      for (const coordinate of feature.geometry.coordinates) {
        const position = this.positionOf(coordinate);
        if (position) bounds.extend(position);
      }
    }
  }

  private positionOf(value: unknown): [number, number] | undefined {
    if (!Array.isArray(value) || value.length < 2) return undefined;
    const [lon, lat] = value;
    return typeof lon === 'number' && typeof lat === 'number' ? [lon, lat] : undefined;
  }

  private routeSourceId(id: string): string {
    return `route-${id}`;
  }

  private routeLineLayerId(id: string): string {
    return `route-${id}-line`;
  }

  private routePointLayerId(id: string): string {
    return `route-${id}-points`;
  }
}
