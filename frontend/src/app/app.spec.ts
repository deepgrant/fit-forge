import { TestBed } from '@angular/core/testing';

import { App } from './app';

describe('App', () => {
  it('creates the merge workspace shell', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Forge split recordings into one ride.');
  });
});
