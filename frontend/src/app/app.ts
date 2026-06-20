import { Component, computed, inject, signal } from '@angular/core';

import { FfmForgeApi, messageOf } from './api-client';
import { distance, duration, fileSize, power, speed, temp, timeRange } from './format';
import type { MergeResponse, SegmentFile, UploadFileResult } from './models';

type Theme = 'light' | 'dark';
type LapStrategy = 'OnePerSegment' | 'KeepOriginal';

@Component({
  selector: 'ffm-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly api = inject(FfmForgeApi);

  protected readonly theme = signal<Theme>('light');
  protected readonly segments = signal<readonly SegmentFile[]>([]);
  protected readonly descriptions = signal<readonly UploadFileResult[]>([]);
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
    }
    this.dryRun.set(null);
    this.merged.set(null);
  }

  protected clearWorkspace(): void {
    this.segments.set([]);
    this.descriptions.set([]);
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
    this.dryRun.set(null);
    this.merged.set(null);
  }

  private setTheme(theme: Theme): void {
    this.theme.set(theme);
    document.documentElement.dataset['theme'] = theme;
    window.localStorage.setItem('ffmforge-theme', theme);
  }
}
