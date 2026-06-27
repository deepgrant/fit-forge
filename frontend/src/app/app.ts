import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, computed, effect, inject, signal } from '@angular/core';

import { FfmForgeApi, isSessionExpired, messageOf } from './api-client';
import { distance, duration, fileSize, power, speed, temp, timeRange } from './format';
import type {
  DeviceInfo,
  DiagnosticIssue,
  DownloadFormat,
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
type EditorRowsBusy = EditorRowsDirection | 'message';
type ReloadDialogKind = 'version' | 'session';

const RouteColors = ['#ff6a1a', '#1f9d6b', '#2f80ed', '#e0453c', '#8b5cf6', '#e0921a', '#008ea8', '#c026d3'];
const OpenFreeMapStyleUrl = 'https://tiles.openfreemap.org/styles/liberty';
const MapLibreCssUrl = 'https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css';
const MapLibreScriptUrl = 'https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js';
const EditorRowsEdgePx = 36;
const EditorMapPickPageSize = 160;
const VersionPollMs = 60 * 60 * 1000;

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
  getCanvas(): HTMLCanvasElement;
  getLayer(id: string): unknown;
  getSource(id: string): GeoJsonSource | undefined;
  on(type: 'load', listener: () => void): void;
  on(type: 'click', listener: (event: MapClickEvent) => void): void;
  on(type: 'mousemove', listener: (event: MapMouseEvent) => void): void;
  on(type: 'mouseout', listener: () => void): void;
  queryRenderedFeatures(point: MapFeatureQueryGeometry, options?: Readonly<Record<string, unknown>>): readonly unknown[];
  remove(): void;
  removeLayer(id: string): void;
  removeSource(id: string): void;
  resize(): void;
}

interface MapPoint {
  readonly x: number;
  readonly y: number;
}

type MapFeatureQueryGeometry = MapPoint | readonly [MapPoint, MapPoint];

interface MapMouseEvent {
  readonly lngLat: {
    readonly lng: number;
    readonly lat: number;
  };
  readonly point: MapPoint;
}

type MapClickEvent = MapMouseEvent;

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

interface ReloadDialog {
  readonly kind: ReloadDialogKind;
  readonly title: string;
  readonly body: string;
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
  private editorMap?: MapLibreMap;
  private mapLoaded = false;
  private editorMapLoaded = false;
  private renderedRouteIds = new Set<string>();
  private editorRouteRendered = false;
  private editorRowsLoadInFlight = false;
  private editorRouteHoverPosition?: [number, number];
  private editorRouteCoordinates: readonly [number, number][] = [];
  private currentFrontendVersion?: string;
  private versionPollId?: ReturnType<typeof window.setInterval>;
  private readonly checkVersionOnFocus = (): void => {
    void this.checkFrontendVersion();
  };

  @ViewChild('mapHost') private mapHost?: ElementRef<HTMLDivElement>;
  @ViewChild('editorMapHost') private editorMapHost?: ElementRef<HTMLDivElement>;
  @ViewChild('editorRecordTable') private editorRecordTable?: ElementRef<HTMLDivElement>;

  protected readonly theme = signal<Theme>('light');
  protected readonly activeView = signal<WorkspaceView>('merge');
  protected readonly segments = signal<readonly SegmentFile[]>([]);
  protected readonly descriptions = signal<readonly UploadFileResult[]>([]);
  protected readonly routeTracks = signal<readonly RouteTrack[]>([]);
  protected readonly dryRun = signal<MergeResponse | null>(null);
  protected readonly merged = signal<MergeResponse | null>(null);
  protected readonly lapStrategy = signal<LapStrategy>('OnePerSegment');
  protected readonly mergeDownloadFormat = signal<DownloadFormat>('fit');
  protected readonly editorDownloadFormat = signal<DownloadFormat>('fit');
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly editorFile = signal<SegmentFile | null>(null);
  protected readonly editorOpen = signal<EditorOpenResponse | null>(null);
  protected readonly editorRows = signal<EditorRowsResponse | null>(null);
  protected readonly editorRowsBusy = signal<EditorRowsBusy | null>(null);
  protected readonly editorMessageType = signal('record');
  protected readonly editorSelectedIssueId = signal<string | null>(null);
  protected readonly editorOperations = signal<readonly RepairOperation[]>([]);
  protected readonly editorPreview = signal<RepairPreview | null>(null);
  protected readonly editorExport = signal<ExportRepairResponse | null>(null);
  protected readonly editorRouteTrack = signal<RouteTrack | null>(null);
  protected readonly editorSelectedRow = signal<EditorRecordRow | null>(null);
  protected readonly reloadDialog = signal<ReloadDialog | null>(null);

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
    const stored = this.localStorage()?.getItem('ffmforge-theme');
    this.setTheme(stored === 'dark' ? 'dark' : 'light');
    effect(() => {
      const tracks = this.routeTracks();
      queueMicrotask(() => this.renderRouteTracks(tracks));
    });
    effect(() => {
      const track = this.editorRouteTrack();
      this.editorRouteCoordinates = track ? this.trackLineCoordinates(track.geojson) : [];
      queueMicrotask(() => this.renderEditorRouteTrack(track));
    });
    effect(() => {
      const row = this.editorSelectedRow();
      queueMicrotask(() => this.renderEditorSelectedRow(row));
    });
  }

  ngAfterViewInit(): void {
    void this.initializeMap();
    this.startVersionMonitor();
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.editorMap?.remove();
    if (this.versionPollId !== undefined) window.clearInterval(this.versionPollId);
    window.removeEventListener('focus', this.checkVersionOnFocus);
  }

  private async initializeMap(): Promise<void> {
    try {
      await this.loadMapLibre();
    } catch (err) {
      this.handleError(err);
      return;
    }

    if (this.mapHost) {
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

    if (this.editorMapHost) {
      this.editorMap = new maplibregl.Map({
        container: this.editorMapHost.nativeElement,
        style: OpenFreeMapStyleUrl,
        center: [-98.5795, 39.8283],
        zoom: 3,
        attributionControl: { compact: true },
      });
      this.editorMap.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
      this.editorMap.on('click', (event) => {
        void this.selectRecordFromEditorMap(event);
      });
      this.editorMap.on('mousemove', (event) => {
        this.updateEditorRouteHover(event);
      });
      this.editorMap.on('mouseout', () => {
        this.clearEditorRouteHover();
      });
      this.editorMap.on('load', () => {
        this.editorMapLoaded = true;
        this.renderEditorRouteTrack(this.editorRouteTrack());
        this.renderEditorSelectedRow(this.editorSelectedRow());
      });
    }
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

  protected setMergeDownloadFormat(format: DownloadFormat): void {
    this.mergeDownloadFormat.set(format);
  }

  protected setEditorDownloadFormat(format: DownloadFormat): void {
    this.editorDownloadFormat.set(format);
  }

  protected toggleTheme(): void {
    this.setTheme(this.theme() === 'dark' ? 'light' : 'dark');
  }

  protected setActiveView(view: WorkspaceView): void {
    this.activeView.set(view);
    queueMicrotask(() => {
      this.map?.resize();
      this.editorMap?.resize();
    });
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
      this.handleError(err);
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
        const download = await this.api.download(merged.id, this.mergeDownloadFormat());
        window.location.assign(download.url);
      }
    } catch (err) {
      this.handleError(err);
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
    this.editorRowsBusy.set('message');
    this.error.set(null);
    try {
      this.editorMessageType.set(messageType);
      this.editorRows.set(await this.api.editorRows(open.id, messageType, 0, 80));
      this.editorSelectedRow.set(null);
    } catch (err) {
      this.handleError(err);
    } finally {
      this.busy.set(null);
      this.editorRowsBusy.set(null);
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
      this.handleError(err);
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

  protected async selectEditorIssue(issue: DiagnosticIssue): Promise<void> {
    this.editorSelectedIssueId.set(issue.id);
    this.editorSelectedRow.set(null);
    await this.showIssueOnEditorMap(issue);
  }

  protected selectEditorRow(row: EditorRecordRow): void {
    this.editorSelectedRow.set(row);
    this.renderEditorIssueSelection(this.emptyGeoJson());
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
      this.handleError(err);
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
      const download = await this.api.download(exported.id, this.editorDownloadFormat());
      window.location.assign(download.url);
    } catch (err) {
      this.handleError(err);
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
      this.handleError(err);
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
    this.editorRouteTrack.set(null);
    this.editorSelectedRow.set(null);
    this.busy.set('Uploading FIT file for editor');
    this.error.set(null);

    try {
      const uploaded = (await this.api.uploadFiles([file])).at(0);
      if (!uploaded) throw new Error('Upload did not return a file id.');
      this.editorFile.set({ ...local, state: 'ready', remoteId: uploaded.id });
      const opened = await this.api.editorOpen(uploaded.id);
      const defaultMessageType = this.defaultEditorMessageType(opened);
      this.editorOpen.set(opened);
      this.editorMessageType.set(defaultMessageType);
      this.editorRows.set(
        defaultMessageType === opened.rows.messageType
          ? opened.rows
          : await this.api.editorRows(uploaded.id, defaultMessageType, 0, 80),
      );
      this.editorSelectedIssueId.set(opened.diagnostics.at(0)?.id ?? null);
      this.editorRouteTrack.set(await this.loadEditorRouteTrack(uploaded.id, file.name));
    } catch (err) {
      const message = messageOf(err);
      this.editorFile.set({ ...local, state: 'failed', error: message });
      this.handleError(err);
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

  private async loadEditorRouteTrack(id: string, name: string): Promise<RouteTrack> {
    return {
      id,
      name,
      color: RouteColors[0],
      geojson: await this.api.track(id),
    };
  }

  private setTheme(theme: Theme): void {
    this.theme.set(theme);
    document.documentElement.dataset['theme'] = theme;
    this.localStorage()?.setItem('ffmforge-theme', theme);
  }

  protected reloadApplication(): void {
    window.location.reload();
  }

  private handleError(err: unknown): void {
    if (isSessionExpired(err)) {
      this.showReloadDialog('session');
    } else {
      this.error.set(messageOf(err));
    }
  }

  private startVersionMonitor(): void {
    void this.checkFrontendVersion();
    this.versionPollId = window.setInterval(() => {
      void this.checkFrontendVersion();
    }, VersionPollMs);
    window.addEventListener('focus', this.checkVersionOnFocus);
  }

  private async checkFrontendVersion(): Promise<void> {
    if (this.reloadDialog()) return;

    const version = await this.frontendVersion();
    if (version !== undefined) {
      if (this.currentFrontendVersion === undefined) {
        this.currentFrontendVersion = version;
      } else if (version !== this.currentFrontendVersion) {
        this.showReloadDialog('version');
        return;
      }
    }

    await this.checkActiveSession();
  }

  private async frontendVersion(): Promise<string | undefined> {
    if (typeof fetch !== 'function') return undefined;

    try {
      const response = await fetch(`version.json?t=${Date.now()}`, { cache: 'no-store' });
      if (!response.ok) return undefined;
      const body = (await response.json()) as unknown;
      if (typeof body !== 'object' || body === null) return undefined;
      const fields = body as Record<string, unknown>;
      return [fields['name'], fields['version'], fields['status']].map((field) => String(field ?? '')).join(':');
    } catch {
      return undefined;
    }
  }

  private showReloadDialog(kind: ReloadDialogKind): void {
    if (this.reloadDialog()) return;
    this.busy.set(null);
    this.editorRowsBusy.set(null);
    this.reloadDialog.set(
      kind === 'version'
        ? {
            kind,
            title: 'FFMForge has been updated',
            body: 'A newer version of the service is available. Reload to use the current frontend and API together.',
          }
        : {
            kind,
            title: 'Session expired',
            body: 'The temporary FIT files for this workspace have expired. Reload and upload the files again to continue.',
          },
    );
  }

  private async checkActiveSession(): Promise<void> {
    const ids = this.activeSessionIds();
    if (ids.length === 0 || this.reloadDialog()) return;

    try {
      await this.api.describe(ids);
    } catch (err) {
      this.handleError(err);
    }
  }

  private activeSessionIds(): string[] {
    return Array.from(
      new Set(
        [
          ...this.readyIds(),
          this.editorFile()?.remoteId,
        ].filter((id): id is string => id !== undefined),
      ),
    );
  }

  private localStorage(): Storage | undefined {
    try {
      return window.localStorage;
    } catch {
      return undefined;
    }
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

  protected editorRowIsSelected(row: EditorRecordRow): boolean {
    const selected = this.editorSelectedRow();
    return selected?.messageType === row.messageType && selected.index === row.index;
  }

  protected editorMapCanSelectRows(): boolean {
    const type = this.editorRows()?.messageType;
    return type !== undefined && this.editorMessageTypeIsMappable(type);
  }

  protected editorCanPickRecordFromMap(): boolean {
    return this.editorOpen()?.anatomy.some((group) => group.name === 'record' && group.count > 0) === true && this.editorRouteTrack() !== null;
  }

  protected editorMessageTypeIsMappable(messageType: string): boolean {
    return messageType === 'record' || messageType === 'lap';
  }

  private defaultEditorMessageType(opened: EditorOpenResponse): string {
    const messageTypes = opened.anatomy.filter((group) => group.count > 0).map((group) => group.name);
    if (messageTypes.includes('record')) return 'record';
    if (messageTypes.includes('lap')) return 'lap';
    return messageTypes.at(0) ?? 'record';
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

  private renderEditorRouteTrack(track: RouteTrack | null): void {
    const map = this.editorMap;
    if (!map || !this.editorMapLoaded) return;

    if (!track) {
      if (this.editorRouteRendered) this.removeEditorRouteLayers();
      this.editorRouteRendered = false;
      return;
    }

    const source = map.getSource('editor-route');
    if (source) {
      source.setData(track.geojson);
    } else {
      map.addSource('editor-route', {
        type: 'geojson',
        data: track.geojson,
      });
    }

    if (!map.getLayer('editor-route-line')) {
      map.addLayer({
        id: 'editor-route-line',
        type: 'line',
        source: 'editor-route',
        filter: ['==', ['get', 'type'], 'track'],
        paint: {
          'line-color': track.color,
          'line-opacity': 0.88,
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 3, 12, 6, 16, 9],
        },
      });
    }

    if (!map.getLayer('editor-route-points')) {
      map.addLayer({
        id: 'editor-route-points',
        type: 'circle',
        source: 'editor-route',
        filter: ['!=', ['get', 'type'], 'track'],
        paint: {
          'circle-color': ['match', ['get', 'type'], 'start', '#1f9d6b', 'finish', '#e0453c', '#e0921a'],
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 7, 4, 13, 7],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 2,
        },
      });
    }

    this.editorRouteRendered = true;
    this.removeEditorSelectionLayers();
    this.renderEditorSelectedRow(this.editorSelectedRow());
    this.renderEditorIssueSelection(this.emptyGeoJson());
    this.fitBoundsOnMap(map, [track.geojson], 38);
    map.resize();
  }

  private renderEditorSelectedRow(row: EditorRecordRow | null): void {
    const map = this.editorMap;
    if (!map || !this.editorMapLoaded) return;

    const data = this.editorSelectionGeoJson(row);
    const source = map.getSource('editor-selected-row');
    if (source) {
      source.setData(data);
    } else {
      map.addSource('editor-selected-row', {
        type: 'geojson',
        data,
      });
    }

    if (!map.getLayer('editor-selected-row-line')) {
      map.addLayer({
        id: 'editor-selected-row-line',
        type: 'line',
        source: 'editor-selected-row',
        filter: ['==', ['geometry-type'], 'LineString'],
        paint: {
          'line-color': '#15181e',
          'line-dasharray': [1.5, 1],
          'line-opacity': 0.82,
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 2, 13, 4],
        },
      });
    }

    if (!map.getLayer('editor-selected-row-point')) {
      map.addLayer({
        id: 'editor-selected-row-point',
        type: 'circle',
        source: 'editor-selected-row',
        filter: ['==', ['geometry-type'], 'Point'],
        paint: {
          'circle-color': '#15181e',
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 7, 7, 13, 10],
          'circle-stroke-color': '#ff6a1a',
          'circle-stroke-width': 4,
        },
      });
    }
  }

  private async showIssueOnEditorMap(issue: DiagnosticIssue): Promise<void> {
    const open = this.editorOpen();
    if (!open || issue.messageType !== 'record') {
      this.renderEditorIssueSelection(this.emptyGeoJson());
      this.editorSelectedRow.set(null);
      return;
    }

    const offset = Math.max(0, issue.startIndex - 8);
    const limit = Math.min(250, issue.endIndex - offset + 10);
    this.editorRowsBusy.set('message');
    try {
      const rows = await this.api.editorRows(open.id, issue.messageType, offset, limit);
      if (this.editorSelectedIssueId() !== issue.id) return;
      this.editorMessageType.set(issue.messageType);
      this.editorRows.set(rows);
      this.editorSelectedRow.set(this.issueTableRow(issue, rows.rows));
      this.scrollSelectedEditorRowIntoView();
      this.renderEditorIssueSelection(this.issueSelectionGeoJson(issue, rows.rows));
    } catch (err) {
      this.handleError(err);
    } finally {
      this.editorRowsBusy.set(null);
    }
  }

  private async selectRecordFromEditorMap(event: MapClickEvent): Promise<void> {
    const open = this.editorOpen();
    const recordTotal = this.editorRecordCount();
    const coordinates = this.editorRouteCoordinates;
    if (!open || recordTotal === 0 || coordinates.length === 0) return;

    const hover = this.editorRouteHoverAt(event);
    if (!hover) return;

    const clicked = this.editorRouteHoverPosition ?? hover;
    const nearestTrackIndex = this.nearestPositionIndex(clicked, coordinates);
    const estimatedRecordIndex = Math.round((nearestTrackIndex / Math.max(1, coordinates.length - 1)) * Math.max(0, recordTotal - 1));
    const limit = Math.min(EditorMapPickPageSize, recordTotal);
    const offset = this.clamp(estimatedRecordIndex - Math.floor(limit / 2), 0, Math.max(0, recordTotal - limit));

    this.editorRowsBusy.set('message');
    this.error.set(null);
    try {
      const rows = await this.api.editorRows(open.id, 'record', offset, limit);
      const selected = this.nearestRecordRow(clicked, rows.rows);
      this.editorMessageType.set('record');
      this.editorRows.set(rows);
      this.editorSelectedIssueId.set(null);
      this.editorSelectedRow.set(selected);
      this.scrollSelectedEditorRowIntoView();
      this.renderEditorIssueSelection(this.emptyGeoJson());
    } catch (err) {
      this.handleError(err);
    } finally {
      this.editorRowsBusy.set(null);
    }
  }

  private updateEditorRouteHover(event: MapMouseEvent): void {
    const hover = this.editorRouteHoverAt(event);
    this.editorRouteHoverPosition = hover;
    this.setEditorMapCursor(hover ? 'copy' : '');
    this.renderEditorRouteHover(hover ?? null);
  }

  private clearEditorRouteHover(): void {
    this.editorRouteHoverPosition = undefined;
    this.setEditorMapCursor('');
    this.renderEditorRouteHover(null);
  }

  private editorRouteHoverAt(event: MapMouseEvent): [number, number] | undefined {
    const map = this.editorMap;
    const coordinates = this.editorRouteCoordinates;
    if (
      !map ||
      !this.editorMapLoaded ||
      coordinates.length === 0 ||
      this.editorRecordCount() === 0 ||
      !map.getLayer('editor-route-line')
    )
      return undefined;

    const features = map.queryRenderedFeatures(this.mapHitArea(event.point, 8), { layers: ['editor-route-line'] });
    if (features.length === 0) return undefined;

    const clicked: [number, number] = [event.lngLat.lng, event.lngLat.lat];
    const nearest = coordinates.at(this.nearestPositionIndex(clicked, coordinates));
    return nearest;
  }

  private setEditorMapCursor(cursor: string): void {
    this.editorMap?.getCanvas().style.setProperty('cursor', cursor);
  }

  private mapHitArea(point: MapPoint, tolerancePx: number): readonly [MapPoint, MapPoint] {
    return [
      { x: point.x - tolerancePx, y: point.y - tolerancePx },
      { x: point.x + tolerancePx, y: point.y + tolerancePx },
    ];
  }

  private renderEditorRouteHover(position: [number, number] | null): void {
    const map = this.editorMap;
    if (!map || !this.editorMapLoaded) return;

    const data = position ? this.positionsGeoJson([position], false) : this.emptyGeoJson();
    const source = map.getSource('editor-route-hover');
    if (source) {
      source.setData(data);
    } else {
      map.addSource('editor-route-hover', {
        type: 'geojson',
        data,
      });
    }

    if (!map.getLayer('editor-route-hover-point')) {
      map.addLayer({
        id: 'editor-route-hover-point',
        type: 'circle',
        source: 'editor-route-hover',
        filter: ['==', ['geometry-type'], 'Point'],
        paint: {
          'circle-color': '#ffffff',
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 7, 7, 13, 11],
          'circle-stroke-color': '#ff6a1a',
          'circle-stroke-width': 4,
        },
      });
    }
  }

  private editorRecordCount(): number {
    return this.editorOpen()?.anatomy.find((group) => group.name === 'record')?.count ?? 0;
  }

  private trackLineCoordinates(track: TrackGeoJson): readonly [number, number][] {
    const line = track.features.find((feature) => feature.geometry.type === 'LineString' && feature.properties?.['type'] === 'track');
    if (!line || !Array.isArray(line.geometry.coordinates)) return [];
    return line.geometry.coordinates
      .map((coordinate) => this.positionOf(coordinate))
      .filter((position): position is [number, number] => position !== undefined);
  }

  private nearestRecordRow(target: [number, number], rows: readonly EditorRecordRow[]): EditorRecordRow | null {
    const positionedRows = rows
      .map((row) => ({ row, position: this.editorRowPositions(row).at(0) }))
      .filter((item): item is { readonly row: EditorRecordRow; readonly position: [number, number] } => item.position !== undefined);
    if (positionedRows.length === 0) return rows.at(0) ?? null;
    const nearestIndex = this.nearestPositionIndex(
      target,
      positionedRows.map((item) => item.position),
    );
    return positionedRows[nearestIndex]?.row ?? null;
  }

  private nearestPositionIndex(target: [number, number], positions: readonly [number, number][]): number {
    let nearestIndex = 0;
    let nearestDistance = Number.POSITIVE_INFINITY;
    positions.forEach((position, index) => {
      const currentDistance = this.positionDistanceSquared(target, position);
      if (currentDistance < nearestDistance) {
        nearestDistance = currentDistance;
        nearestIndex = index;
      }
    });
    return nearestIndex;
  }

  private positionDistanceSquared(a: [number, number], b: [number, number]): number {
    const latScale = Math.cos(((a[1] + b[1]) / 2) * (Math.PI / 180));
    const dx = (a[0] - b[0]) * latScale;
    const dy = a[1] - b[1];
    return dx * dx + dy * dy;
  }

  private clamp(value: number, min: number, max: number): number {
    return Math.min(max, Math.max(min, value));
  }

  private scrollSelectedEditorRowIntoView(): void {
    requestAnimationFrame(() => {
      const table = this.editorRecordTable?.nativeElement;
      const selected = table?.querySelector<HTMLElement>('.editor-row.selected');
      selected?.scrollIntoView({ block: 'center' });
    });
  }

  private renderEditorIssueSelection(data: TrackGeoJson): void {
    const map = this.editorMap;
    if (!map || !this.editorMapLoaded) return;

    const source = map.getSource('editor-issue-selection');
    if (source) {
      source.setData(data);
    } else {
      map.addSource('editor-issue-selection', {
        type: 'geojson',
        data,
      });
    }

    if (!map.getLayer('editor-issue-selection-line')) {
      map.addLayer({
        id: 'editor-issue-selection-line',
        type: 'line',
        source: 'editor-issue-selection',
        filter: ['==', ['geometry-type'], 'LineString'],
        paint: {
          'line-color': '#e0921a',
          'line-dasharray': [1, 1.2],
          'line-opacity': 0.9,
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 3, 13, 5],
        },
      });
    }

    if (!map.getLayer('editor-issue-selection-point')) {
      map.addLayer({
        id: 'editor-issue-selection-point',
        type: 'circle',
        source: 'editor-issue-selection',
        filter: ['==', ['geometry-type'], 'Point'],
        paint: {
          'circle-color': '#e0921a',
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 7, 8, 13, 12],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 3,
        },
      });
    }

    if (data.features.length > 0) {
      this.fitBoundsOnMap(map, [data], 54);
    }
  }

  private removeEditorRouteLayers(): void {
    const map = this.editorMap;
    if (!map) return;
    this.removeEditorSelectionLayers();
    for (const layer of ['editor-route-points', 'editor-route-line']) {
      if (map.getLayer(layer)) map.removeLayer(layer);
    }
    if (map.getSource('editor-route')) map.removeSource('editor-route');
  }

  private removeEditorSelectionLayers(): void {
    const map = this.editorMap;
    if (!map) return;
    for (const layer of [
      'editor-route-hover-point',
      'editor-issue-selection-point',
      'editor-issue-selection-line',
      'editor-selected-row-point',
      'editor-selected-row-line',
    ]) {
      if (map.getLayer(layer)) map.removeLayer(layer);
    }
    for (const source of ['editor-route-hover', 'editor-issue-selection', 'editor-selected-row']) {
      if (map.getSource(source)) map.removeSource(source);
    }
  }

  private issueSelectionGeoJson(issue: DiagnosticIssue, rows: readonly EditorRecordRow[]): TrackGeoJson {
    const issueRows = rows.filter((row) => row.index >= issue.startIndex && row.index <= issue.endIndex);
    const exactPositions = issueRows.flatMap((row) => this.editorRowPositions(row));
    if (exactPositions.length > 0) {
      return this.positionsGeoJson(exactPositions, exactPositions.length > 1);
    }

    const before = rows
      .filter((row) => row.index < issue.startIndex)
      .reverse()
      .flatMap((row) => this.editorRowPositions(row))
      .at(0);
    const after = rows
      .filter((row) => row.index > issue.endIndex)
      .flatMap((row) => this.editorRowPositions(row))
      .at(0);

    if (before && after) {
      return this.positionsGeoJson([this.midpoint(before, after), before, after], true);
    }

    const fallback = before ?? after;
    return fallback ? this.positionsGeoJson([fallback], false) : this.emptyGeoJson();
  }

  private issueTableRow(issue: DiagnosticIssue, rows: readonly EditorRecordRow[]): EditorRecordRow | null {
    return (
      rows.find((row) => row.index >= issue.startIndex && row.index <= issue.endIndex) ??
      rows.find((row) => row.index >= issue.startIndex) ??
      rows.at(-1) ??
      null
    );
  }

  private positionsGeoJson(positions: readonly [number, number][], includeLine: boolean): TrackGeoJson {
    const pointPositions = includeLine && positions.length > 2 ? positions.slice(0, 1) : positions;
    const features: TrackFeature[] = pointPositions.map((position) => ({
      type: 'Feature',
      properties: { type: 'issue' },
      geometry: { type: 'Point', coordinates: position },
    }));

    const linePositions = includeLine && positions.length > 1 ? (positions.length > 2 ? positions.slice(1) : positions) : [];
    if (linePositions.length > 1) {
      features.unshift({
        type: 'Feature',
        properties: { type: 'issue-range' },
        geometry: { type: 'LineString', coordinates: linePositions },
      });
    }

    return { type: 'FeatureCollection', features };
  }

  private midpoint(a: [number, number], b: [number, number]): [number, number] {
    return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  }

  private emptyGeoJson(): TrackGeoJson {
    return { type: 'FeatureCollection', features: [] };
  }

  private editorSelectionGeoJson(row: EditorRecordRow | null): TrackGeoJson {
    const positions = row && (row.messageType === 'record' || row.messageType === 'lap') ? this.editorRowPositions(row) : [];
    const features: TrackFeature[] = positions.map((position, index) => ({
      type: 'Feature',
      properties: { type: index === 0 ? 'selected-start' : 'selected-end' },
      geometry: { type: 'Point', coordinates: position },
    }));

    if (positions.length > 1) {
      features.unshift({
        type: 'Feature',
        properties: { type: 'selected-range' },
        geometry: { type: 'LineString', coordinates: positions },
      });
    }

    return { type: 'FeatureCollection', features };
  }

  private editorRowPositions(row: EditorRecordRow): [number, number][] {
    if (row.messageType === 'record') {
      const position = this.positionFromLatLonText(row.position);
      return position ? [position] : [];
    }

    if (row.messageType === 'lap') {
      const start = this.positionFromNamedFields(row, 'Start latitude', 'Start longitude');
      const end = this.positionFromNamedFields(row, 'End latitude', 'End longitude');
      return [start, end].filter((position): position is [number, number] => position !== undefined);
    }

    return [];
  }

  private positionFromNamedFields(row: EditorRecordRow, latField: string, lonField: string): [number, number] | undefined {
    const lat = this.numberFromField(row, latField);
    const lon = this.numberFromField(row, lonField);
    return lat !== undefined && lon !== undefined ? [lon, lat] : undefined;
  }

  private numberFromField(row: EditorRecordRow, fieldName: string): number | undefined {
    const field = row.fields.find((cell) => cell.field === fieldName);
    if (!field) return undefined;
    const value = Number.parseFloat(field.value);
    return Number.isFinite(value) ? value : undefined;
  }

  private positionFromLatLonText(value: string | undefined): [number, number] | undefined {
    if (!value) return undefined;
    const [latText, lonText] = value.split(',');
    const lat = Number.parseFloat(latText);
    const lon = Number.parseFloat(lonText);
    return Number.isFinite(lat) && Number.isFinite(lon) ? [lon, lat] : undefined;
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
    this.fitBoundsOnMap(map, files, 42);
  }

  private fitBoundsOnMap(map: MapLibreMap, files: readonly TrackGeoJson[], padding: number): void {
    const bounds = new maplibregl.LngLatBounds();
    for (const file of files) {
      for (const feature of file.features) {
        this.extendBounds(bounds, feature);
      }
    }
    if (!bounds.isEmpty()) {
      map.fitBounds(bounds, { padding, maxZoom: 14, duration: 500 });
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
