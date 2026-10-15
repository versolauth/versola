const shell = (active, inner) => `<div class="shell"><aside><div class="brand">Versola</div><div class="tenant">danceliker <span class="mu">▾</span></div>
<nav class="nav"><h6>Tenant scoped</h6><a href="#">Challenges &amp; Security</a><a href="#"${active==='clients'?' class="on"':''}>Clients</a><a href="#">Permissions</a><a href="#">Resources</a><a href="#">Roles</a>
<h6>Global</h6><a href="#">Edges</a><a href="#">Forms</a><a href="#"${active==='themes'?' class="on"':''}>Themes</a><a href="#">Locales</a><a href="#">System Settings</a><a href="#">Tenants</a><a href="#">Users</a></nav>
<div class="foot"><a href="#">Theme</a><a href="#">Log out</a></div></aside><main>${inner}</main></div>`;
// preset = a whole look in one click; every control below stays editable afterwards
const PRESETS = [
  { id: 'studio',   name: 'Studio',   hint: 'Warm, friendly',      brand: 'ff6b4a', shape: 'round', surface: 'bold' },
  { id: 'fintech',  name: 'Fintech',  hint: 'Calm, trustworthy',   brand: '5b5bd6', shape: 'soft',  surface: 'neutral' },
  { id: 'wellness', name: 'Wellness', hint: 'Soft, airy',          brand: '0e9f8e', shape: 'round', surface: 'soft' },
  { id: 'editorial',name: 'Editorial',hint: 'Sharp, serious',      brand: '14130f', shape: 'sharp', surface: 'neutral' },
  { id: 'sunny',    name: 'Sunny',    hint: 'Bold, energetic',     brand: 'f5c518', shape: 'soft',  surface: 'bold' },
  { id: 'berry',    name: 'Berry',    hint: 'Playful, expressive', brand: 'b4348a', shape: 'round', surface: 'bold' },
];
const thumb = p => `<span class="th" data-surface="${p.surface}" data-shape="${p.shape}" style="--b:#${p.brand}"><i class="th-sheet"><i class="th-logo"></i><i class="th-l1"></i><i class="th-f"></i><i class="th-f"></i><i class="th-btn"></i></i></span>`;
