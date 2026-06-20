export function distance(meters?: number): string {
  if (meters === undefined) return '-';
  return `${(meters / 1000).toFixed(2)} km / ${(meters / 1609.344).toFixed(2)} mi`;
}

export function speed(mps?: number): string {
  if (mps === undefined) return '-';
  return `${(mps * 3.6).toFixed(1)} km/h / ${(mps * 2.236936).toFixed(1)} mph`;
}

export function temp(celsius?: number): string {
  if (celsius === undefined) return '-';
  return `${celsius.toFixed(1)} C / ${(celsius * 9.0 / 5.0 + 32.0).toFixed(1)} F`;
}

export function power(watts?: number): string {
  if (watts === undefined) return '-';
  return `${Math.round(watts)} W`;
}

export function duration(seconds?: number): string {
  if (seconds === undefined) return '-';
  const s = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(s / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const rest = s % 60;
  return `${hours}h ${minutes.toString().padStart(2, '0')}m ${rest.toString().padStart(2, '0')}s`;
}

export function fileSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

export function timeRange(start?: string, end?: string): string {
  if (!start || !end) return 'time range pending';
  return `${new Date(start).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} - ${new Date(end).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}
