// PWA only: forwards Riot API calls, because Riot sends no CORS headers so Safari can't call it directly.
// Riot hosts only, a fixed header allowlist, nothing logged or stored.
const HOST = /^https:\/\/([a-z0-9-]+\.)*(riotgames\.com|pvp\.net)\//;
const HEADER = /^(authorization|content-type|x-riot-[a-z-]+)$/i;

module.exports = async (req, res) => {
  const { method, url, headers = {}, body } = req.body || {};
  if (req.method !== 'POST' || !['GET', 'POST', 'PUT'].includes(method) || typeof url !== 'string' || !HOST.test(url))
    return res.status(400).send('Riot API calls only');
  const h = Object.fromEntries(Object.entries(headers).filter(([k, v]) => HEADER.test(k) && typeof v === 'string'));
  try {
    const r = await fetch(url, { method, headers: h, body: method === 'GET' ? undefined : body || undefined, redirect: 'manual' });
    res.status(r.status).setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.send(await r.text());
  } catch (e) {
    res.status(502).send(String(e));
  }
};
