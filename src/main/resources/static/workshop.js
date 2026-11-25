'use strict';

const page = document.querySelector('#main');
const modal = document.querySelector('#dialog');
const toast = document.querySelector('#toast');
const shell = document.querySelector('.shell');
const railToggle = document.querySelector('#rail-toggle');
const names = { DRAFT:'Черновик', RESERVED:'В резерве', APPLIED:'Подтверждён', CANCELLED:'Отменён' };
const actions = { PROFILE:'Профиль', STOCK:'Склад', ORDER:'Заказ', PLAN:'План', RESERVE:'Резерв', CANCEL:'Отмена', FACT:'Фактический раскрой', DEMO:'Демо' };
const data = { profiles:[], stock:[], orders:[], plans:[], journal:[] };
let current = 0;
let messageTimer;

const dateFormat = new Intl.DateTimeFormat('ru-RU', { dateStyle:'medium', timeStyle:'short' });
const date = value => value ? dateFormat.format(new Date(value)) : '—';
const mm = value => new Intl.NumberFormat('ru-RU', { maximumFractionDigits:1, useGrouping:false }).format(value / 10);
const length = value => mm(value) + ' мм';
const state = value => node('span', { class:'state', 'data-state':value }, names[value] || value);
const text = value => document.createTextNode(String(value));
const empty = value => node('p', { class:'empty', role:'status' }, value);

function node(tag, attributes = {}, ...children) {
  const item = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (name === 'click') item.addEventListener('click', value);
    else item.setAttribute(name, value === true ? '' : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child !== undefined && child !== null && child !== false) item.append(child instanceof Node ? child : text(child));
  }
  return item;
}

function icon(name) {
  const svgNamespace = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNamespace, 'svg');
  const use = document.createElementNS(svgNamespace, 'use');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  use.setAttribute('href', '/icons.svg#' + name);
  svg.append(use);
  return svg;
}

const button = (label, click, kind = 'secondary', { glyph, ...extra } = {}) =>
  node('button', { class:'button ' + kind, type:'button', click, ...extra }, glyph && icon(glyph), label);
const label = (name, field) => node('label', {}, node('span', {}, name), field);
const input = (name, options = {}) => node('input', { name, type:'text', ...options });
const select = (name, items, required = true) => node('select', { name, required },
  items.map(([key, value]) => node('option', { value:key }, value)));
const byProfile = id => data.profiles.find(profile => profile.id === id);
const profileLabel = profile => profile ? profile.code + ' · ' + profile.name : '—';
const available = order => order.lines.reduce((total, line) => total + line.quantity - line.produced - line.held, 0);
const profileOptions = () => data.profiles.map(profile => [profile.id, profileLabel(profile)]);
const sum = (items, value) => items.reduce((total, item) => total + value(item), 0);

async function api(path, method = 'GET', body) {
  const options = { method };
  if (body instanceof FormData) options.body = body;
  else if (body !== undefined) {
    options.body = JSON.stringify(body);
    options.headers = { 'Content-Type':'application/json' };
  }
  let response;
  try { response = await fetch(path, options); }
  catch { throw new Error('Нет связи с локальным приложением.'); }
  if (response.status === 204) return null;
  const value = response.headers.get('content-type')?.includes('json') ? await response.json() : {};
  if (!response.ok) throw new Error(value.message || 'Операцию не удалось выполнить.');
  return value;
}

function inform(message) {
  clearTimeout(messageTimer);
  toast.textContent = message;
  toast.hidden = false;
  messageTimer = setTimeout(() => { toast.hidden = true; }, 3500);
}

function header(title, lead, actions) {
  return node('header', { class:'screen-head' },
    node('div', { class:'screen-title' }, node('h1', {}, title),
      typeof lead === 'string' ? node('p', {}, lead) : lead),
    actions && node('div', { class:'screen-actions' }, actions));
}

function section(title, action, body) {
  return node('section', { class:'panel' }, node('header', { class:'panel-head' }, node('h2', {}, title), action), body);
}

function table(columns, rows) {
  return node('div', { class:'table-wrap' }, node('table', {},
    node('thead', {}, node('tr', {}, columns.map(label => node('th', { scope:'col' }, label)))),
    node('tbody', {}, rows.map(row => node('tr', {}, row.map(value => node('td', {}, value)))))));
}

function metric(title, value, glyph, href) {
  const tag = href ? 'a' : 'div';
  return node(tag, { class:'metric', href },
    node('span', { class:'metric-icon' }, icon(glyph)),
    node('div', { class:'metric-body' }, node('span', {}, title), node('strong', {}, value)),
    href && node('span', { class:'metric-arrow' }, icon('arrow-up-right')));
}

function dialog(title, body, submitLabel, submit) {
  let busy = false;
  const error = node('p', { class:'error', role:'alert', hidden:true });
  const cancel = button('Отмена', () => { if (!busy) modal.close(); });
  const save = node('button', { class:'button primary', type:'submit' }, submitLabel);
  const form = node('form', { class:'modal-form' }, body, error, node('div', { class:'modal-actions' }, cancel, save));
  modal.replaceChildren(node('header', { class:'modal-head' }, node('h2', { id:'dialog-title' }, title),
    button('×', () => { if (!busy) modal.close(); }, 'close', { 'aria-label':'Закрыть' })), form);
  modal.oncancel = event => { if (busy) event.preventDefault(); };
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy) return;
    busy = true;
    save.disabled = true;
    cancel.disabled = true;
    error.hidden = true;
    try {
      const after = await submit(new FormData(form), form);
      modal.close();
      if (typeof after === 'function') after();
      else await reload();
    } catch (reason) {
      error.textContent = reason.message;
      error.hidden = false;
    } finally {
      busy = false;
      save.disabled = false;
      cancel.disabled = false;
    }
  });
  modal.showModal();
}

function newProfile() {
  dialog('Новый профиль', node('div', { class:'fields' },
    label('Код профиля', input('code', { maxlength:40, required:true, autofocus:true, placeholder:'Например, T40' })),
    label('Название', input('name', { maxlength:100, required:true, placeholder:'Труба 40 × 20 × 2' }))),
  'Добавить', async form => {
    await api('/api/profiles','POST', { code:form.get('code'), name:form.get('name') });
    inform('Профиль добавлен');
  });
}

function newStock() {
  dialog('Приёмка заготовок', node('div', { class:'fields' },
    label('Профиль', select('profileId', profileOptions())),
    label('Тип материала', select('kind', [['FULL','Целая заготовка'],['OFFCUT','Обрезок']])),
    node('div', { class:'split' },
      label('Длина, мм', input('length', { inputmode:'decimal', maxlength:8, required:true, placeholder:'6000' })),
      label('Количество, шт.', input('quantity', { type:'number', min:1, max:1000, step:1, value:1, required:true })))),
  'Принять на склад', async form => {
    const values = Object.fromEntries(form.entries());
    await api('/api/stock','POST', { ...values, profileId:Number(values.profileId), quantity:Number(values.quantity) });
    inform('Заготовки приняты на склад');
  });
}

function stockView() {
  const groups = new Map();
  for (const stock of data.stock) {
    const key = [stock.profileId,stock.kind,stock.length,stock.state].join(':');
    const bucket = groups.get(key);
    if (bucket) bucket.quantity++;
    else groups.set(key, { ...stock, quantity:1 });
  }
  const rows = [...groups.values()].map(item => [
    node('strong', {}, profileLabel(byProfile(item.profileId))), node('span', { class:'number' }, length(item.length)),
    item.kind === 'OFFCUT' ? 'Обрезок' : 'Целая', item.quantity,
    item.state === 'AVAILABLE' ? node('span', { class:'state available' }, 'Доступна') : state('RESERVED'),
  ]);
  return [
    header('Склад', 'Целые заготовки и остатки, пригодные для следующего раскроя.',
      [button('Новый профиль', newProfile, 'secondary', { glyph:'plus' }),
        button('Принять заготовки', newStock, 'primary', { disabled:!data.profiles.length, glyph:'package' })]),
    node('div', { class:'work-grid stock-grid' },
      section('Заготовки', null, rows.length ? table(['Профиль','Длина','Вид','Шт.','Состояние'], rows) :
        empty(data.profiles.length ? 'На складе пока нет заготовок.' : 'Добавьте профиль, чтобы принять первые заготовки.')),
      section('Профили', null, data.profiles.length
        ? table(['Код','Название'], data.profiles.map(profile => [node('strong', { class:'number' }, profile.code), profile.name]))
        : empty('Профили пока не добавлены.'))),
  ];
}

function orderFields() {
  return node('div', { class:'split' },
    label('Название заказа', input('name', { required:true, maxlength:120, autofocus:true })),
    label('Заказчик', input('customer', { maxlength:100 })));
}

function newOrder() {
  const list = node('div', { class:'line-editor' });
  function addLine() {
    const row = node('div', { class:'order-line' });
    row.append(
      label('Профиль', select('profileId', profileOptions())),
      label('Длина, мм', input('length', { inputmode:'decimal', maxlength:8, required:true, placeholder:'1450' })),
      label('Количество', input('quantity', { type:'number', min:1, max:2000, value:1, required:true })),
      button('×', () => { if (list.childElementCount > 1) row.remove(); }, 'close', { 'aria-label':'Убрать позицию' }));
    if (list.childElementCount < 200) list.append(row);
  }
  addLine();
  dialog('Новый заказ', [orderFields(), list, button('+ Добавить позицию', addLine, 'secondary')],
    'Создать заказ', async form => {
      const codes = form.getAll('profileId');
      const lengths = form.getAll('length');
      const numbers = form.getAll('quantity');
      await api('/api/orders','POST', { name:form.get('name'),customer:form.get('customer'),
        lines:codes.map((id,index) => ({ profileId:Number(id), length:lengths[index], quantity:Number(numbers[index]) })) });
      inform('Заказ создан');
    });
}

function importOrder() {
  dialog('Импорт заказа', [orderFields(),
    label('CSV из Excel', input('file', { type:'file', accept:'.csv,text/csv', required:true })),
    node('p', { class:'hint' }, 'Колонки: Профиль, Длина, Количество. Для профиля используйте его код из справочника. ',
      node('a', { href:'/template.csv', download:'шаблон-заказа.csv' }, 'Скачать шаблон'))],
  'Импортировать', async form => {
    await api('/api/orders/import','POST',form);
    inform('Заказ импортирован');
  });
}

function ordersView() {
  const cards = data.orders.map(order => {
    const quantity = sum(order.lines, line => line.quantity);
    const produced = sum(order.lines, line => line.produced);
    const held = sum(order.lines, line => line.held);
    return node('details', { class:'order-card' }, node('summary', {},
      node('div', {}, node('span', { class:'order-code' }, '№' + order.id), node('strong', {}, order.name),
        order.customer && node('small', {}, order.customer)),
      node('span', { class:'order-count' }, produced + ' / ' + quantity),
      node('progress', { max:quantity, value:produced, 'aria-label':'Выполнено деталей' }),
      held > 0 && node('span', { class:'state', 'data-state':'RESERVED' }, held + ' в резерве')),
    table(['Профиль','Длина','Заказано','Выполнено','В резерве'],
      order.lines.map(line => [profileLabel(byProfile(line.profileId)), length(line.length), line.quantity, line.produced, line.held])));
  });
  return [
    header('Заказы', 'Позиции, выполнение и резерв деталей.',
      [button('Импорт CSV', importOrder, 'secondary', { disabled:!data.profiles.length }),
        button('Новый заказ', newOrder, 'primary', { disabled:!data.profiles.length })]),
    cards.length ? node('section', { class:'orders-list' }, cards) : empty(data.profiles.length ? 'Заказы пока не добавлены.' : 'Сначала добавьте профиль на складе.'),
  ];
}

function planForm() {
  const candidates = data.orders.filter(order => available(order) > 0);
  if (!candidates.length) return;
  dialog('Рассчитать раскрой', [
    node('fieldset', { class:'order-selection' }, node('legend', {}, 'Заказы'),
      candidates.map(order => node('label', {},
        input('orders', { type:'checkbox', value:order.id }),
        node('span', {}, '№' + order.id + ' · ' + order.name),
        node('small', {}, available(order) + ' дет.')))),
    node('div', { class:'triple' },
      label('Пропил, мм', input('kerf', { inputmode:'decimal', value:'3', maxlength:8, required:true })),
      label('Торцовка на конец, мм', input('trim', { inputmode:'decimal', value:'5', maxlength:8, required:true })),
      label('Мин. обрезок, мм', input('reusable', { inputmode:'decimal', value:'250', maxlength:8, required:true }))),
    node('p', { class:'hint' }, 'Пропил учитывается на каждую деталь. Торцовка снимается с двух концов заготовки.'),
  ], 'Рассчитать', async form => {
    const orderIds = form.getAll('orders').map(Number);
    if (!orderIds.length) throw new Error('Выберите хотя бы один заказ.');
    const details = await api('/api/plans','POST', { orderIds, kerf:form.get('kerf'), trim:form.get('trim'), reusable:form.get('reusable') });
    return () => { location.hash = '#plan/' + details.plan.id; };
  });
}

function plansView() {
  const open = data.orders.filter(order => available(order) > 0).length;
  const stock = data.stock.filter(item => item.state === 'AVAILABLE');
  const held = data.plans.filter(plan => plan.state === 'RESERVED').length;
  const latest = data.plans[0];
  const emptyPlan = !data.profiles.length ? 'Добавьте профиль и заготовки, затем создайте заказ.'
    : open ? 'Выберите заказы и рассчитайте первый план.' : 'Создайте заказ, чтобы получить первую карту раскроя.';
  const stockTotals = node('dl', { class:'readout-list' },
    [['Целые заготовки', stock.filter(item => item.kind === 'FULL').length],
      ['Пригодные обрезки', stock.filter(item => item.kind === 'OFFCUT').length],
      ['Профили', data.profiles.length]].map(([title,value]) =>
      node('div', {}, node('dt', {}, title), node('dd', {}, value))));
  const registry = data.plans.length
    ? table(['План','Состояние','Заказы','Заготовки','Детали','Недостача','Создан'],
      data.plans.map(plan => [node('a', { href:'#plan/' + plan.id, class:'record' },
        'План №' + plan.id, icon('arrow-up-right')), state(plan.state), plan.orders, plan.bars, plan.pieces,
        node('span', { class:plan.shortage ? 'danger-text' : '' }, plan.shortage), date(plan.createdAt)]))
    : node('div', { class:'blueprint-empty' }, icon('ruler'), empty(emptyPlan),
      !open && node('a', { href:data.profiles.length ? '#orders' : '#stock', class:'button secondary' },
        data.profiles.length ? 'К заказам' : 'К складу', icon('arrow-up-right')));
  return [
    header('Планы раскроя', 'Карты резов и фактический расход материала',
      button('Рассчитать раскрой', planForm, 'primary', { disabled:!open, glyph:'scissors' })),
    node('section', { class:'metrics', 'aria-label':'Сводка мастерской' },
      metric('Доступные заготовки',stock.length,'package','#stock'),
      metric('Заказы к расчёту',open,'list-checks','#orders'),
      metric('Планы в резерве',held,'layers')),
    node('div', { class:'work-grid' },
      section('Карты раскроя', null, registry),
      node('div', { class:'side-stack' },
        latest && section('Последний расчёт',
          node('a', { class:'icon-link', href:'#plan/' + latest.id, 'aria-label':'Открыть последний расчёт' }, icon('arrow-up-right')),
          node('div', { class:'latest-card' },
            node('div', { class:'latest-line' }, node('a', { class:'record', href:'#plan/' + latest.id }, 'План №' + latest.id), state(latest.state)),
            node('dl', { class:'readout-list' }, [['Детали',latest.pieces],['Заготовки',latest.bars],['Недостача',latest.shortage]].map(([title,value]) =>
              node('div', {}, node('dt', {}, title), node('dd', { class:title === 'Недостача' && value ? 'danger-text' : '' }, value)))))),
        section('Остатки на складе', null, stockTotals))),
  ];
}

function journalView() {
  return [header('Журнал', 'Приёмка, резервы и подтверждение производства.'),
    section('Последние операции', null, data.journal.length ? table(['Дата','Операция','Запись'],
      data.journal.map(item => [date(item.at), actions[item.category] || item.category, item.detail])) : empty('Журнал пока пуст.'))];
}

function planParameters(details) {
  const { plan, orderIds } = details;
  const orderLabels = orderIds.map(id => {
    const order = data.orders.find(item => item.id === id);
    return order ? '№' + id + ' ' + order.name : '№' + id;
  });
  const fields = [
    ['Пропил',length(plan.kerf)],
    ['Торцовка на конец',length(plan.trim)],
    ['Мин. обрезок',length(plan.reusable)],
    ['Создан',date(plan.createdAt)],
  ];
  if (plan.confirmedAt) fields.push(['Подтверждён', date(plan.confirmedAt)]);
  return node('section', { class:'plan-meta panel' },
    node('header', { class:'panel-head' }, node('h2', {}, 'Параметры')),
    node('dl', { class:'readout-list' }, fields.map(([name,value]) =>
      node('div', {}, node('dt', {}, name), node('dd', {}, value)))),
    node('div', { class:'plan-orders' }, node('strong', {}, 'Заказы'), node('ul', {}, orderLabels.map(order => node('li', {}, order)))));
}

function strip(bar, plan) {
  const actual = plan.state === 'APPLIED';
  const active = bar.cuts.map(cut => ({ ...cut, units:actual ? cut.actual || 0 : cut.quantity })).filter(cut => cut.units > 0);
  const remainder = actual ? bar.actual ?? bar.expected : bar.expected;
  const track = node('div', { class:'cut-strip', 'aria-hidden':'true' });
  function segment(value, kind, title, index = 0, units = 1) {
    if (value <= 0) return;
    const part = kind === 'part';
    const color = part ? ' ' + ['cut-a','cut-b','cut-c','cut-d'][index % 4] : '';
    const cell = node('span', { class:'cut-segment ' + kind + color, title }, part && value / bar.inputLength > .12 ? title : '');
    cell.style.flexGrow = String(value);
    if (part && units > 1) cell.style.setProperty('--unit-width', (100 / units) + '%');
    track.append(cell);
  }
  if (active.length) segment(plan.trim, 'trim', 'Торцовка');
  active.forEach((cut, index) => segment((cut.length + plan.kerf) * cut.units, 'part', mm(cut.length) + ' × ' + cut.units, index, cut.units));
  segment(remainder, remainder >= plan.reusable ? 'reusable' : 'scrap', 'Остаток ' + length(remainder));
  const used = sum(active, cut => (cut.length + plan.kerf) * cut.units);
  segment(bar.inputLength - used - remainder - (active.length ? plan.trim * 2 : 0), 'scrap', 'Дополнительные потери');
  if (active.length) segment(plan.trim, 'trim', 'Торцовка');
  return node('div', { class:'bar-diagram', 'aria-hidden':'true' },
    node('div', { class:'bar-ruler' }, node('span', {}, '0'), node('span', {}, mm(bar.inputLength) + ' мм')),
    track);
}

function barCard(bar, plan) {
  const applied = plan.state === 'APPLIED';
  const remainder = applied ? bar.actual ?? bar.expected : bar.expected;
  const rows = bar.cuts.map(cut => [
    '№' + cut.orderId + ' · ' + cut.orderName,
    length(cut.length), cut.quantity,
    ...(applied ? [cut.actual ?? '—'] : []),
  ]);
  return node('article', { class:'bar-card' },
    node('header', { class:'bar-head' },
      node('div', {}, node('span', { class:'bar-number' }, 'Заготовка №' + bar.stockId),
        node('h3', {}, bar.code + ' · ' + bar.profile)),
      node('div', { class:'bar-length' }, node('strong', {}, length(bar.inputLength)), node('small', {}, bar.kind === 'OFFCUT' ? 'Обрезок' : 'Целая'))),
    strip(bar, plan),
    table(['Заказ','Размер детали','По плану',...(applied ? ['Факт'] : [])], rows),
    node('footer', { class:'bar-footer' },
      node('span', {}, applied ? 'Фактический остаток' : 'Расчётный остаток', ' ', node('strong', {}, length(remainder))),
      applied && bar.offcutId ? node('span', { class:'reusable-tag' }, 'На складе №' + bar.offcutId) :
        !applied && remainder >= plan.reusable ? node('span', { class:'reusable-tag' }, 'Пригодный обрезок') : null,
      applied && bar.cuts.every(cut => !cut.actual) ? node('span', { class:'reusable-tag' }, 'Возвращена целиком') : null));
}

function openFact(details) {
  const controls = details.bars.map(bar => {
    const remainder = input('remainder', { inputmode:'decimal', maxlength:8 });
    const maxText = node('p', { class:'hint', role:'status' });
    const counts = bar.cuts.map(cut => {
      const field = input('quantity', { type:'number', required:true, step:1, min:0, max:cut.quantity, value:cut.quantity });
      return { cut, field };
    });
    function reflect() {
      const made = sum(counts, item => Number(item.field.value) || 0);
      const used = sum(counts, item => (Number(item.field.value) || 0) * (item.cut.length + details.plan.kerf));
      const max = Math.max(0, made ? bar.inputLength - 2 * details.plan.trim - used : bar.inputLength);
      remainder.placeholder = mm(max);
      remainder.disabled = !made;
      if (!made) remainder.value = '';
      maxText.textContent = made ? 'Расчётный предел: ' + length(max) : 'Заготовка вернётся на склад целиком.';
    }
    counts.forEach(item => item.field.addEventListener('input', reflect));
    reflect();
    const card = node('fieldset', { class:'fact-bar' },
      node('legend', {}, 'Заготовка №' + bar.stockId + ' · ' + bar.code),
      counts.map(({ cut,field }) => label('№' + cut.orderId + ' · ' + length(cut.length) + ' · до ' + cut.quantity + ' шт.', field)),
      label('Измеренный остаток, мм', remainder), maxText);
    return { card, bar, remainder, counts };
  });
  dialog('Фактический раскрой', [
    node('p', { class:'hint' }, 'Укажите, сколько деталей вырезали. Если остаток не измеряли, оставьте поле пустым для расчёта.'),
    node('div', { class:'fact-list' }, controls.map(item => item.card)),
  ], 'Списать по факту', async () => {
    const bars = controls.map(item => ({ id:item.bar.id, remainder:item.remainder.value,
      cuts:item.counts.map(({cut,field}) => ({ lineId:cut.lineId, quantity:Number(field.value) })) }));
    await api('/api/plans/' + details.plan.id + '/confirm','POST',{ bars });
    inform('Фактический раскрой записан');
  });
}

function planAction(details, key) {
  const id = details.plan.id;
  const reserving = key === 'reserve';
  const title = reserving ? 'Зарезервировать план №' + id : 'Отменить план №' + id;
  const copy = reserving
    ? 'Заготовки и запланированные детали будут закреплены за этой картой до подтверждения или отмены.'
    : details.plan.state === 'RESERVED'
      ? 'Заготовки и детали вернутся в доступный остаток.'
      : 'Черновик останется в журнале как отменённый.';
  dialog(title, node('p', { class:'hint' }, copy), reserving ? 'Зарезервировать' : 'Подтвердить отмену', async () => {
    await api('/api/plans/' + id + '/' + key,'POST');
    inform(reserving ? 'Резерв создан' : 'План отменён');
  });
}

function planView(details) {
  const plan = details.plan;
  const bars = details.bars;
  const shortage = details.shortages;
  const controls = [
    button('Печать карты', () => window.print(), 'secondary', { glyph:'printer' }),
    ...(plan.state === 'DRAFT' ? [button('В резерв', () => planAction(details, 'reserve'), 'primary', { disabled:!bars.length, glyph:'layers' })] : []),
    ...(plan.state === 'RESERVED' ? [button('Внести факт', () => openFact(details), 'primary')] : []),
    ...(['DRAFT','RESERVED'].includes(plan.state) ? [button('Отменить план', () => planAction(details, 'cancel'), 'quiet')] : []),
  ];
  return [
    node('a', { href:'#plans', class:'back-link' }, '← Все планы'),
    header('План №' + plan.id, state(plan.state), controls),
    node('div', { class:'work-grid plan-grid' },
      node('div', { class:'plan-side' }, planParameters(details),
        shortage.length > 0 && section('Недостача при расчёте', icon('triangle-alert'),
          node('ul', { class:'shortage-list' }, shortage.map(item => node('li', {},
            node('div', {},
              node('strong', { class:'number' }, item.code + ' · ' + length(item.length)),
              node('span', {}, '№' + item.orderId + ' · ' + item.orderName)),
            node('strong', { class:'number danger-text' }, item.quantity + ' шт.')))))),
      bars.length ? node('section', { class:'bar-board', 'aria-label':'Карта резов' },
        node('header', { class:'board-head' }, node('h2', {}, 'Карта резов'),
          node('ul', { class:'cut-key', 'aria-hidden':'true' },
            [['part','Детали'],['reusable','Обрезок'],['scrap','Потери']].map(([kind,title]) =>
              node('li', {}, node('i', { class:kind }), title)))),
        node('div', { class:'bar-list' }, bars.map(bar => barCard(bar, plan))))
      : node('div', { class:'bar-board' }, empty('Подходящих заготовок для выбранных деталей нет.'))),
  ];
}

function activeRoute() {
  const path = location.hash.slice(1);
  const match = /^plan\/(\d+)$/.exec(path);
  if (match) return { view:'plans', plan:Number(match[1]) };
  return { view:['stock','orders','journal'].includes(path) ? path : 'plans', plan:null };
}

async function render() {
  const token = ++current;
  const route = activeRoute();
  document.querySelectorAll('nav [data-view]').forEach(link => {
    if (link.dataset.view === route.view) link.setAttribute('aria-current','page');
    else link.removeAttribute('aria-current');
  });
  document.title = route.plan ? 'План №' + route.plan + ' · Раскрой профиля' : 'Раскрой профиля';
  document.querySelector('#demo').hidden = data.profiles.length !== 0;
  if (route.plan) {
    page.replaceChildren(node('p', { class:'startup', role:'status' }, 'Открываем план…'));
    try {
      const details = await api('/api/plans/' + route.plan);
      if (token === current) page.replaceChildren(...planView(details));
    } catch (reason) {
      if (token === current) page.replaceChildren(node('p', { class:'error', role:'alert' }, reason.message),
        node('a', { href:'#plans', class:'back-link' }, '← Все планы'));
    }
    return;
  }
  const views = { plans:plansView, stock:stockView, orders:ordersView, journal:journalView };
  page.replaceChildren(...views[route.view]());
}

async function reload() {
  try {
    Object.assign(data, await api('/api/workspace'));
    await render();
  } catch (reason) {
    page.replaceChildren(node('p', { class:'error', role:'alert' }, reason.message), button('Повторить', reload));
  }
}

document.querySelector('#demo').addEventListener('click', () => {
  dialog('Загрузить демо-данные?', node('p', { class:'hint' },
    'В пустой базе появятся три профиля, заготовки с обрезками и четыре учебных заказа.'), 'Загрузить демо', async () => {
    await api('/api/demo','POST');
    inform('Учебные данные добавлены');
  });
});
function reflectRail(expanded) {
  const title = expanded ? 'Свернуть меню' : 'Развернуть меню';
  shell.dataset.rail = expanded ? 'expanded' : 'compact';
  railToggle.setAttribute('aria-expanded', String(expanded));
  railToggle.setAttribute('aria-label', title);
  railToggle.title = title;
}

try { reflectRail(localStorage.getItem('cut.rail-expanded') === 'true'); } catch {}
railToggle.addEventListener('click', () => {
  const expanded = shell.dataset.rail !== 'expanded';
  reflectRail(expanded);
  try { localStorage.setItem('cut.rail-expanded', String(expanded)); } catch {}
});
document.querySelector('#skip').addEventListener('click', event => {
  event.preventDefault();
  page.focus();
});
window.addEventListener('hashchange', () => { void reload(); });
void reload();
