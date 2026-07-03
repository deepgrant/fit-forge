import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { App } from './app';
import type { EditorOpenResponse } from './models';

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
});
