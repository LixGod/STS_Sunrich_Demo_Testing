export function fromUtc(value: string | null | undefined, zone: string): Date | null {
  if (!value) return null;
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value));
  const part = (name: string) => Number(parts.find((p) => p.type === name)?.value ?? 0);
  return new Date(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
}

export function toUtc(value: Date | null, zone: string): string | null {
  if (!value) return null;
  const wanted = Date.UTC(value.getFullYear(), value.getMonth(), value.getDate(), value.getHours(), value.getMinutes(), value.getSeconds());
  const zonedMillis = (time: number) => {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(time));
    const part = (name: string) => Number(parts.find((p) => p.type === name)?.value ?? 0);
    return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
  };
  let guess = wanted;
  for (let i = 0; i < 3; i++) guess += wanted - zonedMillis(guess);
  if (zonedMillis(guess) !== wanted) return null;
  for (const minutes of [-120, -60, -30, 30, 60, 120]) {
    if (zonedMillis(guess + minutes * 60_000) === wanted) return null;
  }
  return new Date(guess).toISOString();
}
