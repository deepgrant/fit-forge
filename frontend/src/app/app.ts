import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, computed, effect, inject, signal } from '@angular/core';

import { FfmForgeApi, messageOf } from './api-client';
import { distance, duration, fileSize, power, speed, temp, timeRange } from './format';
import type { MergeResponse, RouteTrack, SegmentFile, TrackFeature, TrackGeoJson, UploadFileResult } from './models';

type Theme = 'light' | 'dark';
type LapStrategy = 'OnePerSegment' | 'KeepOriginal';

const RouteColors = ['#ff6a1a', '#1f9d6b', '#2f80ed', '#e0453c', '#8b5cf6', '#e0921a', '#008ea8', '#c026d3'];
const OpenFreeMapStyleUrl = 'https://tiles.openfreemap.org/styles/liberty';
const MapLibreCssUrl = 'https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css';
const MapLibreScriptUrl = 'https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js';

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

  @ViewChild('mapHost') private mapHost?: ElementRef<HTMLDivElement>;

  protected readonly theme = signal<Theme>('light');
  protected readonly segments = signal<readonly SegmentFile[]>([]);
  protected readonly descriptions = signal<readonly UploadFileResult[]>([]);
  protected readonly routeTracks = signal<readonly RouteTrack[]>([]);
  protected readonly dryRun = signal<MergeResponse | null>(null);
  protected readonly merged = signal<MergeResponse | null>(null);
  protected readonly lapStrategy = signal<LapStrategy>('OnePerSegment');
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly readyIds = computed(() =>
    this.segments()
      .map((segment) => segment.remoteId)
      .filter((id): id is string => id !== undefined),
  );
  protected readonly canMerge = computed(() => this.readyIds().length >= 2 && this.busy() === null);
  protected readonly primaryActivity = computed(() => this.descriptions().at(0));
  protected readonly report = computed(() => this.dryRun()?.report ?? this.merged()?.report);
  protected readonly devices = computed(() => this.descriptions().flatMap((file) => file.devices));
  protected readonly totalRecords = computed(() => this.report()?.totalRecords ?? this.descriptions().reduce((sum, file) => sum + file.layout.totalMessages, 0));
  protected readonly gapSeconds = computed(() => this.report()?.gaps.reduce((sum, gap) => sum + gap.seconds, 0) ?? 0);
  protected readonly gapWeight = computed(() => Math.max(1, this.gapSeconds()));
  protected readonly displayDistanceM = computed(() => this.report()?.totalDistanceM ?? this.primaryActivity()?.summary.totalDistanceM);
  protected readonly displayMovingSeconds = computed(() => this.report()?.movingSeconds ?? this.primaryActivity()?.summary.movingSeconds);
  protected readonly displayElapsedSeconds = computed(() => this.report()?.elapsedSeconds ?? this.primaryActivity()?.summary.elapsedSeconds);

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
