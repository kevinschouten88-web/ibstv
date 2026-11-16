/**
 * IBStv demo-server
 * =================
 * Een kleine server die zich gedraagt als een Xtream Codes-server, zodat de keurders
 * van Apple en Google IBStv kunnen testen. Alle inhoud is legaal te gebruiken:
 *  - films en series: open films van de Blender Foundation (CC BY), via de openbare
 *    voorbeeldmap van Google;
 *  - zenders: openbare teststreams van Apple, Mux en Akamai.
 * Posters en zenderlogo's tekent deze server zelf.
 *
 * Inloggen in IBStv:
 *   Server-URL:     het adres van deze Worker, bijvoorbeeld https://ibstv-demo.<naam>.workers.dev
 *   Gebruikersnaam: demo
 *   Wachtwoord:     ibstv-demo
 *
 * Plaatsen: Cloudflare > Workers & Pages > Create > Worker > deze code plakken > Deploy.
 */

const USER = 'demo';
const PASS = 'ibstv-demo';

const G = 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/';
const CREDIT = 'Een open film van de Blender Foundation (CC BY 3.0, blender.org).';

// Zenders (openbare teststreams)
const LIVE = [
  {id: 1, num: 1, name: 'Demo Live', cat: 1, color: ['#6E5BFF', '#9686FF'], url: 'https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8'},
  {id: 2, num: 2, name: 'Demo Film', cat: 1, color: ['#0FA3A0', '#7EF0C0'], url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'},
  {id: 3, num: 3, name: 'Demo Test', cat: 2, color: ['#FF4F64', '#FFB36E'], url: 'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8'},
  {id: 4, num: 4, name: 'Demo Studio', cat: 2, color: ['#3A4BFF', '#5CD0FF'], url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'}
];
const LIVE_CATS = [{category_id: '1', category_name: 'Demo', parent_id: 0}, {category_id: '2', category_name: 'Test', parent_id: 0}];

// Films (Blender Foundation, CC BY)
const MOVIES = [
  {id: 101, name: 'Big Buck Bunny', year: '2008', dur: '00:09:56', genre: 'Animatie, Komedie', rating: 7.2, cat: 10, file: 'BigBuckBunny.mp4', color: ['#5BA84A', '#F2D25C'],
   plot: 'Een goedmoedig groot konijn neemt het op tegen drie pestende knaagdieren.', director: 'Sacha Goedegebure', country: 'Nederland'},
  {id: 102, name: 'Elephants Dream', year: '2006', dur: '00:10:53', genre: 'Animatie, Sciencefiction', rating: 6.5, cat: 10, file: 'ElephantsDream.mp4', color: ['#7A4B2A', '#F08A3C'],
   plot: 'Twee mannen dwalen door een vreemde, mechanische wereld die niet is wat ze denken.', director: 'Bassam Kurdali', cast: 'Cas Jansen, Tygo Gernandt', country: 'Nederland'},
  {id: 103, name: 'Sintel', year: '2010', dur: '00:14:48', genre: 'Animatie, Avontuur', rating: 7.5, cat: 10, file: 'Sintel.mp4', color: ['#2F4F7F', '#9AD1FF'],
   plot: 'Een jonge vrouw zoekt naar de kleine draak die ze ooit verzorgde.', director: 'Colin Levy', cast: 'Halina Reijn, Thom Hoffman', country: 'Nederland'},
  {id: 104, name: 'Tears of Steel', year: '2012', dur: '00:12:14', genre: 'Sciencefiction', rating: 6.8, cat: 11, file: 'TearsOfSteel.mp4', color: ['#30343F', '#8FA3BF'],
   plot: 'Een groep wetenschappers probeert Amsterdam te redden van robots uit de toekomst.', director: 'Ian Hubert', cast: 'Derek de Lint, Sergio Hasselbaink, Vanja Rukavina', country: 'Nederland'}
];
const VOD_CATS = [{category_id: '10', category_name: 'Animatie', parent_id: 0}, {category_id: '11', category_name: 'Sciencefiction', parent_id: 0}];

// Series: korte clips uit dezelfde map
const SERIES = [
  {id: 201, name: 'Korte films', cat: 20, color: ['#9A3CFF', '#FF7BC0'], plot: 'Korte voorbeeldclips om de serieweergave van IBStv te testen.',
   seasons: {1: [['ForBiggerBlazes.mp4', 'Vuur'], ['ForBiggerEscapes.mp4', 'Ontsnapping'], ['ForBiggerFun.mp4', 'Plezier']],
             2: [['ForBiggerJoyrides.mp4', 'Rit'], ['ForBiggerMeltdowns.mp4', 'Smelten']]}}
];
const SERIES_CATS = [{category_id: '20', category_name: 'Demo', parent_id: 0}];

const ADDED = 1763164800; // vaste datum, zodat "nieuw toegevoegd" altijd hetzelfde is

/* ---------- Hulpjes ---------- */
const json = (o, status = 200) => new Response(JSON.stringify(o), {status, headers: {
  'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store'}});
const b64 = s => btoa(unescape(encodeURIComponent(s)));
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));

function poster(title, [a, b]){
  const words = title.toUpperCase().split(' ');
  const lines = []; let cur = '';
  for(const w of words){ if((cur + ' ' + w).trim().length > 11){ if(cur) lines.push(cur); cur = w; } else cur = (cur + ' ' + w).trim(); }
  if(cur) lines.push(cur);
  const text = lines.map((l, i) => `<text x="200" y="${470 + i * 44 - (lines.length - 1) * 22}" text-anchor="middle" font-family="Arial Black,Arial,sans-serif" font-weight="900" font-size="40" fill="#fff">${esc(l)}</text>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600" viewBox="0 0 400 600">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${b}"/><stop offset="1" stop-color="${a}"/></linearGradient>
<linearGradient id="h" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".75"/></linearGradient></defs>
<rect width="400" height="600" fill="url(#g)"/><circle cx="270" cy="190" r="86" fill="#fff" opacity=".85"/>
<path d="M0 360 Q100 300 200 345 T400 330 V600 H0Z" fill="#000" opacity=".25"/><path d="M0 410 Q120 360 240 400 T400 395 V600 H0Z" fill="#000" opacity=".3"/>
<rect y="300" width="400" height="300" fill="url(#h)"/>${text}
<text x="200" y="566" text-anchor="middle" font-family="Arial,sans-serif" font-size="13" fill="#fff" opacity=".7" letter-spacing="2">IBSTV DEMO</text></svg>`;
}
function logo(name, [a, b]){
  return `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="140" viewBox="0 0 240 140">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs>
<rect width="240" height="140" rx="28" fill="url(#g)"/><text x="120" y="88" text-anchor="middle" font-family="Arial Black,Arial,sans-serif" font-weight="900" font-size="38" fill="#fff">${esc(name.replace('Demo ', '').toUpperCase())}</text></svg>`;
}
const svg = s => new Response(s, {headers: {'Content-Type': 'image/svg+xml', 'Cache-Control': 'public, max-age=86400', 'Access-Control-Allow-Origin': '*'}});

// Programmagids: blokken van een half uur rond nu
const SHOWS = ['Ochtendjournaal', 'Natuur in beeld', 'Techniek van morgen', 'Korte animaties', 'Muziek live', 'Het weer', 'Reizen dichtbij', 'Avondnieuws'];
function epg(id, limit){
  const now = Math.floor(Date.now() / 1000), slot = 1800, start = now - (now % slot);
  const out = [];
  for(let i = 0; i < Math.max(2, Math.min(10, limit || 4)); i++){
    const s = start + i * slot, n = Math.floor(s / slot) + id * 3;
    const title = SHOWS[n % SHOWS.length];
    out.push({id: String(s), epg_id: String(id), channel_id: 'demo' + id, lang: 'nl',
      title: b64(title), description: b64('Een demo-uitzending op ' + LIVE.find(x => x.id === id).name + '. Alleen bedoeld om IBStv te testen.'),
      start: new Date(s * 1000).toISOString().replace('T', ' ').slice(0, 19), end: new Date((s + slot) * 1000).toISOString().replace('T', ' ').slice(0, 19),
      start_timestamp: String(s), stop_timestamp: String(s + slot)});
  }
  return {epg_listings: out};
}

/* ---------- Server ---------- */
export default {
  async fetch(req){
    const url = new URL(req.url), base = url.origin, p = url.pathname;
    if(req.method === 'OPTIONS') return new Response(null, {headers: {'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*'}});

    // Afbeeldingen
    let m = p.match(/^\/img\/(poster|logo)\/(\d+)\.svg$/);
    if(m){
      const id = Number(m[2]);
      if(m[1] === 'logo'){ const c = LIVE.find(x => x.id === id); return c ? svg(logo(c.name, c.color)) : new Response('', {status: 404}); }
      const it = MOVIES.find(x => x.id === id) || SERIES.find(x => x.id === id);
      if(it) return svg(poster(it.name, it.color));
      const ep = id >= 1000 ? SERIES[0] : null;
      return ep ? svg(poster(ep.name, ep.color)) : new Response('', {status: 404});
    }

    // Afspelen: /live|movie|series/user/pass/id.ext
    m = p.match(/^\/(live|movie|series)\/([^/]+)\/([^/]+)\/(\d+)\.\w+$/);
    if(m){
      if(decodeURIComponent(m[2]) !== USER || decodeURIComponent(m[3]) !== PASS) return new Response('Forbidden', {status: 403});
      const id = Number(m[4]);
      let to = null;
      if(m[1] === 'live') to = LIVE.find(x => x.id === id)?.url;
      else if(m[1] === 'movie') to = MOVIES.find(x => x.id === id) && G + MOVIES.find(x => x.id === id).file;
      else { const e = episodes().find(x => x.id === String(id)); to = e && G + e.file; }
      return to ? Response.redirect(to, 302) : new Response('Not found', {status: 404});
    }

    if(p !== '/player_api.php' && p !== '/get.php') {
      return new Response('IBStv demo-server. Log in met gebruikersnaam "demo" en wachtwoord "ibstv-demo".', {headers: {'Content-Type': 'text/plain; charset=utf-8'}});
    }
    const q = url.searchParams;
    const ok = q.get('username') === USER && q.get('password') === PASS;
    const action = q.get('action') || '';
    if(!ok) return json(action ? [] : {user_info: {auth: 0}});

    switch(action){
      case '': return json({
        user_info: {username: USER, password: PASS, message: 'IBStv demo', auth: 1, status: 'Active', exp_date: null, is_trial: '0',
          active_cons: '0', created_at: String(ADDED), max_connections: '1', allowed_output_formats: ['m3u8', 'ts']},
        server_info: {url: url.hostname, port: '443', https_port: '443', server_protocol: 'https', timezone: 'Europe/Amsterdam',
          timestamp_now: Math.floor(Date.now() / 1000), time_now: new Date().toISOString().replace('T', ' ').slice(0, 19)}});
      case 'get_live_categories': return json(LIVE_CATS);
      case 'get_live_streams': return json(LIVE.filter(c => !q.get('category_id') || String(c.cat) === q.get('category_id')).map(c => ({
        num: c.num, name: c.name, stream_type: 'live', stream_id: c.id, stream_icon: `${base}/img/logo/${c.id}.svg`,
        epg_channel_id: 'demo' + c.id, added: String(ADDED), category_id: String(c.cat), tv_archive: 0, direct_source: ''})));
      case 'get_short_epg': case 'get_simple_data_table': return json(epg(Number(q.get('stream_id')), Number(q.get('limit'))));
      case 'get_vod_categories': return json(VOD_CATS);
      case 'get_vod_streams': return json(MOVIES.filter(v => !q.get('category_id') || String(v.cat) === q.get('category_id')).map((v, i) => ({
        num: i + 1, name: v.name, stream_type: 'movie', stream_id: v.id, stream_icon: `${base}/img/poster/${v.id}.svg`, rating: String(v.rating),
        added: String(ADDED + i * 3600), category_id: String(v.cat), container_extension: 'mp4', direct_source: ''})));
      case 'get_vod_info': {
        const v = MOVIES.find(x => x.id === Number(q.get('vod_id')));
        if(!v) return json({info: [], movie_data: {}});
        return json({info: {name: v.name, plot: v.plot + ' ' + CREDIT, description: v.plot + ' ' + CREDIT, genre: v.genre, releasedate: v.year + '-01-01',
          duration: v.dur, rating: String(v.rating), director: v.director || '', cast: v.cast || '', country: v.country || '', movie_image: `${base}/img/poster/${v.id}.svg`, cover_big: `${base}/img/poster/${v.id}.svg`},
          movie_data: {stream_id: v.id, name: v.name, added: String(ADDED), category_id: String(v.cat), container_extension: 'mp4'}});
      }
      case 'get_series_categories': return json(SERIES_CATS);
      case 'get_series': return json(SERIES.map((s, i) => ({num: i + 1, name: s.name, series_id: s.id, cover: `${base}/img/poster/${s.id}.svg`,
        plot: s.plot, genre: 'Demo', releaseDate: '2014', rating: '7', last_modified: String(ADDED), category_id: String(s.cat)})));
      case 'get_series_info': {
        const s = SERIES.find(x => x.id === Number(q.get('series_id')));
        if(!s) return json({episodes: {}});
        const eps = {};
        for(const e of episodes()) (eps[e.season] = eps[e.season] || []).push({id: e.id, episode_num: e.num, title: e.title, container_extension: 'mp4', season: e.season,
          info: {plot: 'Een korte voorbeeldclip van Google, bedoeld om te testen.', movie_image: `${base}/img/poster/${s.id}.svg`}});
        return json({info: {name: s.name, plot: s.plot, cover: `${base}/img/poster/${s.id}.svg`}, episodes: eps,
          seasons: Object.keys(s.seasons).map(n => ({season_number: Number(n), name: 'Seizoen ' + n}))});
      }
      default: return json([]);
    }
  }
};

function episodes(){
  const out = []; let id = 1000;
  for(const [sn, list] of Object.entries(SERIES[0].seasons))
    list.forEach(([file, title], i) => out.push({id: String(++id), season: sn, num: i + 1, title, file}));
  return out;
}
