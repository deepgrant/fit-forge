import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { App } from './app';
import type { EditorOpenResponse } from './models';

interface StringSignal {
  set(value: string | null): void;
}

describe('App', () => {
  it('creates the merge workspace shell', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Forge split recordings into one ride.');
  });

  it('switches to the editor workspace shell', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const editorButton = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Editor',
    );
    editorButton?.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Inspect and repair a FIT file.');
  });

  it('defaults downloads to FIT and lets each workspace choose GPX', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Merge & download .fit');
    expect(fixture.nativeElement.textContent).toContain('Save repaired .fit');

    const mergeFormat = fixture.nativeElement.querySelector('[aria-label="Merge download format"]');
    const mergeGpx = Array.from<HTMLButtonElement>(mergeFormat.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'GPX',
    );
    mergeGpx?.click();

    const editorFormat = fixture.nativeElement.querySelector('[aria-label="Editor download format"]');
    const editorGpx = Array.from<HTMLButtonElement>(editorFormat.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'GPX',
    );
    editorGpx?.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Merge & download .gpx');
    expect(fixture.nativeElement.textContent).toContain('Save repaired .gpx');
  });

  it('renders sensor settings in the editor devices panel', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    const component = fixture.componentInstance as unknown as {
      activeView: { set(value: 'editor'): void };
      editorOpen: { set(value: EditorOpenResponse): void };
    };
    const response: EditorOpenResponse = {
      id: 'sample-id',
      summary: {},
      devices: [],
      sensors: [
        {
          index: 0,
          manufacturer: 'Garmin',
          product: 9999,
          kind: 'cadence',
          name: 'CAD Pinarello',
          antId: '6-1-7A-6CA2',
          sourceType: 'antplus',
          wheelSizeAutoMm: 2122,
        },
      ],
      layout: { counts: [{ type: 'sensor', count: 1 }], totalMessages: 1, totalFields: 10 },
      anatomy: [{ name: 'sensor', count: 1, status: 'ok', issues: 0 }],
      diagnostics: [],
      rows: { messageType: 'sensor', offset: 0, limit: 80, total: 1, rows: [] },
      verification: { status: 'upload-safe', canExport: true, checks: [] },
    };

    component.activeView.set('editor');
    component.editorOpen.set(response);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('CAD Pinarello');
    expect(fixture.nativeElement.textContent).toContain('Cadence');
    expect(fixture.nativeElement.textContent).toContain('ANT 6-1-7A-6CA2');
  });

  it('renders aliased manufacturer logos in the editor devices panel', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    const component = fixture.componentInstance as unknown as {
      activeView: { set(value: 'editor'): void };
      editorOpen: { set(value: EditorOpenResponse): void };
    };
    const response: EditorOpenResponse = {
      id: 'sample-id',
      summary: {},
      devices: [
        {
          index: 8,
          manufacturer: 'Polar Electro',
          productName: 'Polar H10',
          product: 2,
          softwareVersion: 1,
          sourceType: 'antplus',
        },
      ],
      sensors: [],
      layout: { counts: [{ type: 'device_info', count: 1 }], totalMessages: 1, totalFields: 8 },
      anatomy: [{ name: 'device_info', count: 1, status: 'ok', issues: 0 }],
      diagnostics: [],
      rows: { messageType: 'device_info', offset: 0, limit: 80, total: 1, rows: [] },
      verification: { status: 'upload-safe', canExport: true, checks: [] },
    };

    component.activeView.set('editor');
    component.editorOpen.set(response);
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const logo = root.querySelector<HTMLImageElement>('.editor-device-list .manufacturer-mark img');
    expect(logo?.getAttribute('src')).toBe('brands/polar.svg');
    expect(fixture.nativeElement.textContent).toContain('Polar H10');
  });

  it('infers manufacturer logos from known device names and product ids', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    const component = fixture.componentInstance as unknown as {
      activeView: { set(value: 'editor'): void };
      editorOpen: { set(value: EditorOpenResponse): void };
    };
    const response: EditorOpenResponse = {
      id: 'sample-id',
      summary: {},
      devices: [
        {
          index: 9,
          manufacturer: 'unknown',
          productName: 'SRAM Eagle',
          product: 1016,
          sourceType: 'antplus',
        },
        {
          index: 12,
          manufacturer: 'unknown',
          product: 2567,
          sourceType: 'antplus',
        },
      ],
      sensors: [
        {
          index: 13,
          manufacturer: 'unknown',
          product: 3578,
          kind: 'bike_power',
          name: 'Rally:0537480',
          sourceType: 'antplus',
        },
      ],
      layout: { counts: [{ type: 'device_info', count: 2 }], totalMessages: 3, totalFields: 12 },
      anatomy: [{ name: 'device_info', count: 2, status: 'ok', issues: 0 }],
      diagnostics: [],
      rows: { messageType: 'device_info', offset: 0, limit: 80, total: 2, rows: [] },
      verification: { status: 'upload-safe', canExport: true, checks: [] },
    };

    component.activeView.set('editor');
    component.editorOpen.set(response);
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const logoSrcs = Array.from(root.querySelectorAll<HTMLImageElement>('.editor-device-list .manufacturer-mark img')).map((logo) =>
      logo.getAttribute('src'),
    );
    expect(logoSrcs).toContain('brands/sram.svg');
    expect(logoSrcs).toContain('brands/garmin.svg');
    expect(fixture.nativeElement.textContent).toContain('SRAM Eagle');
    expect(fixture.nativeElement.textContent).toContain('Headlight');
  });

  it('dims the merge workspace while uploads are in progress', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    const component = fixture.componentInstance as unknown as {
      mergeUploadBusy: StringSignal;
    };

    component.mergeUploadBusy.set('Uploading FIT files');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.workspace-busy-overlay')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.workspace.workspace-busy')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Uploading FIT files');
  });

  it('dims the editor map while route rendering is in progress', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    const component = fixture.componentInstance as unknown as {
      activeView: { set(value: 'editor'): void };
      editorMapBusy: StringSignal;
    };

    component.activeView.set('editor');
    component.editorMapBusy.set('Drawing route map');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.editor-map-shell.map-loading')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.map-busy-overlay')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Drawing route map');
  });
});
