import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { App } from './app';

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
});
