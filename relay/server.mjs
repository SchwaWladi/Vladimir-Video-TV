import http from 'node:http';
import { randomBytes } from 'node:crypto';

const origin = new URL(process.env.PUBLIC_ORIGIN ?? 'http://localhost:8080');
if (origin.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(origin.hostname))
  throw new Error('Set PUBLIC_ORIGIN to the public HTTPS origin');
const sessions = new Map();
const tickets = new Map();
const ttl = 10 * 60_000;
const token = () => randomBytes(24).toString('hex');
function remove(key, session) {
  sessions.delete(key);
  tickets.delete(session.ticket);
}
function reply(res, status, body, type = 'application/json') {
  res.writeHead(status, {
    'Content-Type': `${type}; charset=utf-8`,
    'Cache-Control': 'no-store',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; form-action 'self'; frame-ancestors 'none'",
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(body);
}
const json = (res, status, value) => reply(res, status, JSON.stringify(value));
const html = (res, status, body) => reply(res, status,
  '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Владимир Видео ТВ</title>' + body + '</html>', 'text/html');
async function readForm(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 16_384) throw new Error('Form too large');
    chunks.push(chunk);
  }
  return new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
}
const server = http.createServer(async (req, res) => {
  try {
    const now = Date.now();
    for (const [key, session] of sessions) if (session.expires <= now) remove(key, session);
    const path = new URL(req.url, origin).pathname;
    if (req.method === 'POST' && path === '/api/pair') {
      if (sessions.size >= 256) return json(res, 503, { error: 'Relay busy' });
      const key = token();
      const ticket = token();
      sessions.set(key, { ticket, expires: now + ttl });
      tickets.set(ticket, key);
      return json(res, 201, { key, link: new URL('/send/' + ticket, origin).href });
    }
    if (req.method === 'GET' && path === '/api/pair') {
      const key = req.headers.authorization?.replace(/^Bearer /, '');
      const session = sessions.get(key);
      if (!session) return json(res, 410, { error: 'Expired' });
      if (!session.source) return json(res, 200, { pending: true });
      remove(key, session);
      return json(res, 200, session.source);
    }
    const ticket = /^\/send\/([0-9a-f]{48})$/.exec(path)?.[1];
    const session = sessions.get(tickets.get(ticket));
    if (!session || session.source) return html(res, 410, '<h1>Ссылка истекла или уже использована</h1>');
    if (req.method === 'GET') return html(res, 200,
      '<h1>Владимир Видео ТВ</h1><p>Отправь ссылку. На телевизоре её нужно будет подтвердить.</p>' +
      '<form method="post"><p><label>Назначение <select name="kind"><option value="tv">Телеканалы M3U</option>' +
      '<option value="vod">Видеотека M3U</option></select></label></p><p><label>Название <input name="name" maxlength="80"></label></p>' +
      '<p><label>Адрес плейлиста <input name="url" type="url" maxlength="4096" required></label></p><button>Отправить на ТВ</button></form>');
    if (req.method !== 'POST') return json(res, 405, { error: 'Method not allowed' });
    const form = await readForm(req);
    const kind = form.get('kind');
    const url = form.get('url')?.trim();
    let address;
    try { address = new URL(url); } catch {}
    if (!['tv', 'vod'].includes(kind) || !address || !['http:', 'https:'].includes(address.protocol) || url.length > 4096)
      return html(res, 400, '<h1>Нужна ссылка HTTP(S) на M3U</h1>');
    if (!tickets.has(ticket) || session.source || Date.now() >= session.expires)
      return html(res, 410, '<h1>Ссылка истекла или уже использована</h1>');
    session.source = { kind, url, name: form.get('name')?.trim().slice(0, 80) ?? '' };
    tickets.delete(ticket);
    return html(res, 200, '<h1>Ссылка отправлена</h1><p>Подтверди её на телевизоре.</p>');
  } catch {
    if (!res.headersSent) json(res, 400, { error: 'Invalid request' });
  }
});
server.requestTimeout = 15_000;
server.listen(Number(process.env.PORT ?? 8080), process.env.BIND ?? '127.0.0.1');
