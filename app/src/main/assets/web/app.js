'use strict'

/*
 * 浅笔记事 · 电脑协作端。
 *
 * 和手机端共用同一套书本数据（手机是存储的真相），这里只做界面与 IO：
 *  - 拉书列表 / 目录树 / 单节内容；
 *  - 编辑正文与简介，改标题，新建卷章，删除，上下移动，换父级；
 *  - 自动保存（停止输入 2.5 秒后）+ Ctrl+S 立即保存；
 *  - 保存时带上读到的 modifiedAt 作 base，服务器发现别处改过就回 409，由用户选覆盖还是载入。
 */

const $ = (id) => document.getElementById(id)

const state = {
  code: '',
  books: [],
  bookId: null,
  book: null,
  tree: [],
  nodeId: null,
  node: null,
  base: 0,
  dirty: false,
  saving: false,
  saveTimer: null,
  pollTimer: null,
  conflictNode: null,
}

// ───────────────────────── 与手机通信 ─────────────────────────

async function api(path, params = {}, body = null) {
  const url = new URL(path, location.origin)
  if (state.code) url.searchParams.set('t', state.code)
  for (const key of Object.keys(params)) {
    const value = params[key]
    if (value !== undefined && value !== null) url.searchParams.set(key, value)
  }
  const init = { headers: {} }
  if (body !== null) {
    init.method = 'POST'
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  let response
  try {
    response = await fetch(url, init)
  } catch (e) {
    return { status: 0, data: { ok: false, error: '连不上手机了，看看服务器还开着吗' } }
  }
  let data
  try {
    data = await response.json()
  } catch (e) {
    data = { ok: false, error: '服务器返回了看不懂的内容' }
  }
  if (response.status === 401) showGate('访问码不对，再看看手机上的那一串')
  return { status: response.status, data }
}

// ───────────────────────── 启动 ─────────────────────────

function boot() {
  // 手机上给的完整 URL 带了 ?t=访问码，进来看一眼就记住，之后不用再输
  const fromUrl = new URLSearchParams(location.search).get('t')
  if (fromUrl) {
    state.code = fromUrl
    try { localStorage.setItem('qianbi-code', fromUrl) } catch (e) { /* 无痕模式 */ }
  } else {
    try { state.code = localStorage.getItem('qianbi-code') || '' } catch (e) { state.code = '' }
  }
  // 把访问码从地址栏抹掉，免得留在浏览历史或截屏里
  if (location.search) history.replaceState(null, '', location.pathname)

  bindEvents()
  if (!state.code) {
    showGate('输入手机上显示的访问码')
    return
  }
  start()
}

async function start() {
  hideGate()
  const { status, data } = await api('/api/books')
  if (status === 401) {
    showGate('访问码不对，再看看手机上的那一串')
    return
  }
  if (!data.ok) {
    setConn(data.error || '连不上')
    startPolling()
    return
  }
  state.books = data.books
  renderBooks()
  setConn('已连接 · ' + state.books.length + ' 本可编辑')
  startPolling()
  if (state.books.length && !state.bookId) openBook(state.books[0].id)
}

// ───────────────────────── 访问码门 ─────────────────────────

function showGate(hint) {
  $('app').classList.add('hidden')
  $('gate').classList.remove('hidden')
  if (hint) $('gate-hint').textContent = hint
  $('gate-input').value = ''
  $('gate-input').focus()
}

function hideGate() {
  $('gate').classList.add('hidden')
  $('app').classList.remove('hidden')
}

async function submitGate() {
  const value = $('gate-input').value.trim()
  if (!value) return
  state.code = value
  try { localStorage.setItem('qianbi-code', value) } catch (e) { /* 无痕模式 */ }
  await start()
}

// ───────────────────────── 书架 ─────────────────────────

/** 和手机端同一套语义：`#标签` 之间是「与」，其余文字按书名 / 作者 / 简介模糊匹配。 */
function matchesSearch(book, query) {
  const tags = []
  const text = query
    .replace(/#([^\s#]+)/g, (whole, tag) => {
      tags.push(tag.toLowerCase())
      return ' '
    })
    .trim()
    .toLowerCase()

  if (tags.length) {
    const owned = (book.tags || []).map((t) => t.toLowerCase())
    const hit = tags.every((want) => owned.some((have) => have === want || have.includes(want)))
    if (!hit) return false
  }
  if (text) {
    const hay = [book.title, book.author, book.summary].map((s) => (s || '').toLowerCase())
    if (!hay.some((s) => s.includes(text))) return false
  }
  return true
}

function renderBooks() {
  const list = $('book-list')
  list.innerHTML = ''
  const query = $('search').value
  const books = state.books.filter((book) => matchesSearch(book, query))
  if (!books.length) {
    list.innerHTML = '<li class="placeholder">没有匹配的书</li>'
    return
  }
  for (const book of books) {
    const li = document.createElement('li')
    li.className = 'book' + (book.id === state.bookId ? ' active' : '')

    const title = document.createElement('span')
    title.className = 'book-title'
    title.textContent = book.title

    const meta = document.createElement('span')
    meta.className = 'book-meta'
    meta.textContent = [book.author, (book.chars || 0) + ' 字'].filter(Boolean).join(' · ')

    li.append(title, meta)
    li.addEventListener('click', () => openBook(book.id))
    list.append(li)
  }
}

async function openBook(id) {
  if (state.dirty && !confirm('当前这一节还没保存，切换会丢掉改动。要继续吗？')) return
  const { data } = await api('/api/book', { id })
  if (!data.ok) {
    toast(data.error || '打不开这本书')
    return
  }
  state.bookId = id
  state.book = data.book
  state.tree = data.tree
  state.nodeId = null
  state.node = null
  state.dirty = false
  renderBooks()
  renderBookHead()
  renderTree()
  showEditor(false)
  if (state.tree.length) selectNode(state.tree[0].id)
}

function renderBookHead() {
  if (!state.book) return
  $('toc-title').textContent = state.book.title
  $('toc-count').textContent = state.tree.length + ' 节 · ' + (state.book.chars || 0) + ' 字'
}

// ───────────────────────── 目录 ─────────────────────────

function renderTree() {
  const list = $('tree')
  list.innerHTML = ''
  for (const row of state.tree) {
    const li = document.createElement('li')
    li.className = 'node ' + row.kind + (row.id === state.nodeId ? ' active' : '')
    // 缩进量、层级竖线的位置全在 CSS 里用这个变量算，一眼能看出谁是子级
    li.style.setProperty('--depth', String(row.depth))

    // 内层这一块才是「卡片」：色标贴着它的左边缘，于是跟着层级一起往右缩进
    const inner = document.createElement('div')
    inner.className = 'node-inner'

    const main = document.createElement('div')
    main.className = 'node-main'

    const title = document.createElement('span')
    title.className = 'node-title'
    title.textContent = row.title

    const ops = document.createElement('span')
    ops.className = 'ops'
    ops.append(
      opButton('↑', '上移', () => moveNode(row.id, -1)),
      opButton('↓', '下移', () => moveNode(row.id, 1)),
      opButton('✎', '改标题（简介在右边直接改）', () => renameNode(row)),
      opButton('↰', '移到上一层', () => moveOut(row)),
      opButton('✕', '删除这一节及其全部子级', () => removeNode(row)),
    )

    main.append(title, ops)
    inner.append(main)

    if (row.summary) {
      const summary = document.createElement('span')
      summary.className = 'node-summary'
      summary.textContent = row.summary
      inner.append(summary)
    }

    li.append(inner)
    li.addEventListener('click', (event) => {
      if (event.target.closest('.ops')) return
      selectNode(row.id)
    })
    list.append(li)
  }
}

function opButton(label, tip, action) {
  const button = document.createElement('button')
  button.className = 'op'
  button.textContent = label
  button.title = tip
  button.addEventListener('click', (event) => {
    event.stopPropagation()
    action()
  })
  return button
}

// ───────────────────────── 单节读写 ─────────────────────────

async function selectNode(id) {
  if (state.dirty && !confirm('当前这一节还没保存，切换会丢掉改动。要继续吗？')) return
  const { data } = await api('/api/node', { id: state.bookId, node: id })
  if (!data.ok) {
    toast(data.error || '读不到这一节')
    return
  }
  state.nodeId = id
  state.node = data.node
  state.base = data.node.modifiedAt
  state.dirty = false
  hideConflict()
  fillEditor()
  renderTree()
}

function fillEditor() {
  const node = state.node
  if (!node) {
    showEditor(false)
    return
  }
  showEditor(true)
  $('node-title').value = node.title
  $('node-kind').textContent = node.kind === 'group' ? '卷' : '章'
  $('node-summary').value = node.summary || ''
  $('node-content').value = node.writable ? node.content || '' : ''
  $('node-content').disabled = !node.writable
  // 只靠灰底不够明确，把原因直接写在输入框里
  $('node-content').placeholder = node.writable
    ? '正文…'
    : '「卷 / 目录」不承载正文 · 此处不可修改'
  $('locked-hint').classList.toggle('hidden', node.writable)
  updateChars()
  setSaveState('clean')
}

function showEditor(visible) {
  $('editor').classList.toggle('hidden', !visible)
  $('editor-empty').classList.toggle('hidden', visible)
}

function updateChars() {
  const text = $('node-content').value
  const chars = text.replace(/\s/g, '').length
  $('node-chars').textContent = state.node && state.node.writable ? chars + ' 字' : ''
}

function markDirty() {
  state.dirty = true
  setSaveState('dirty')
  clearTimeout(state.saveTimer)
  state.saveTimer = setTimeout(() => save(false), 2500)
}

async function save(force) {
  if (!state.node || state.saving) return
  if (!state.dirty && !force) return
  clearTimeout(state.saveTimer)

  const payload = {
    id: state.bookId,
    node: state.nodeId,
    title: $('node-title').value,
    summary: $('node-summary').value,
    // 覆盖模式下把 base 归零：服务器就不再拿旧时间戳拦我们
    base: force ? 0 : state.base,
  }
  if (state.node.writable) payload.content = $('node-content').value

  state.saving = true
  setSaveState('saving')
  const { status, data } = await api('/api/node/save', {}, payload)
  state.saving = false

  if (status === 409) {
    state.conflictNode = data.node || null
    $('conflict').classList.remove('hidden')
    setSaveState('conflict')
    return
  }
  if (!data.ok) {
    setSaveState('failed')
    toast(data.error || '保存失败')
    return
  }
  state.base = data.modifiedAt
  if (state.node) state.node.modifiedAt = data.modifiedAt
  state.dirty = false
  setSaveState('saved')
  await reloadBook()
}

function setSaveState(kind) {
  const el = $('save-state')
  const labels = {
    clean: '未改动',
    dirty: '待保存…',
    saving: '保存中…',
    saved: '已保存',
    conflict: '有冲突',
    failed: '保存失败',
  }
  const stamp = kind === 'saved' ? ' ' + new Date().toLocaleTimeString('zh-CN', { hour12: false }) : ''
  el.textContent = (labels[kind] || '') + stamp
  el.className = 'save-state ' + kind
}

async function reloadBook() {
  if (!state.bookId) return
  const { data } = await api('/api/book', { id: state.bookId })
  if (!data.ok) return
  state.book = data.book
  state.tree = data.tree
  renderBookHead()
  renderTree()
}

// ───────────────────────── 结构操作 ─────────────────────────

/** 新建位置：选中卷 → 挂到卷下面；选中章 → 和它同级；没选 → 最外层。 */
function parentForNew() {
  const current = state.tree.find((row) => row.id === state.nodeId)
  if (!current) return ''
  return current.kind === 'group' ? current.id : current.parentId || ''
}

async function createNode(kind) {
  if (!state.bookId) {
    toast('先挑一本书')
    return
  }
  const fallback = kind === 'group' ? '新卷' : '新章'
  const input = prompt(kind === 'group' ? '卷名' : '章名', fallback)
  if (input === null) return
  const { data } = await api('/api/node/create', {}, {
    id: state.bookId,
    parent: parentForNew(),
    kind,
    title: input.trim() || fallback,
  })
  if (!data.ok) {
    toast(data.error || '新建失败')
    return
  }
  await reloadBook()
  selectNode(data.node)
}

async function renameNode(row) {
  const input = prompt('新标题', row.title)
  if (input === null) return
  const title = input.trim()
  if (!title || title === row.title) return
  const { data } = await api('/api/node/update', {}, { id: state.bookId, node: row.id, title })
  if (!data.ok) {
    toast(data.error || '改名失败')
    return
  }
  if (state.nodeId === row.id && state.node) {
    // 服务端改标题会推进节点时间戳：自己的 base 必须跟上，否则下次保存会误判成冲突
    state.node.title = title
    state.node.modifiedAt = data.modifiedAt || state.node.modifiedAt
    state.base = state.node.modifiedAt
    $('node-title').value = title
  }
  await reloadBook()
}

async function removeNode(row) {
  if (!confirm('删除「' + row.title + '」？它下面的所有内容会一起删掉，无法恢复。')) return
  const { data } = await api('/api/node/delete', {}, { id: state.bookId, node: row.id })
  if (!data.ok) {
    toast(data.error || '删除失败')
    return
  }
  if (state.nodeId === row.id) {
    state.nodeId = null
    state.node = null
    state.dirty = false
    showEditor(false)
  }
  await reloadBook()
}

async function moveNode(id, delta) {
  const { data } = await api('/api/node/move', {}, { id: state.bookId, node: id, delta })
  if (!data.ok) {
    toast(data.error || '移动失败')
    return
  }
  await reloadBook()
}

/** 往上挪一层（挂到当前父级的同级去）。 */
async function moveOut(row) {
  const current = state.tree.find((item) => item.id === row.id)
  if (!current || !current.parentId) {
    toast('已经在最外层了')
    return
  }
  const parent = state.tree.find((item) => item.id === current.parentId)
  const target = parent ? parent.parentId || '' : ''
  const { data } = await api('/api/node/move', {}, { id: state.bookId, node: row.id, parent: target })
  if (!data.ok) {
    toast(data.error || '移动失败')
    return
  }
  await reloadBook()
}

// ───────────────────────── 冲突 ─────────────────────────

function hideConflict() {
  state.conflictNode = null
  $('conflict').classList.add('hidden')
}

function loadServerVersion() {
  const node = state.conflictNode
  if (!node) return
  state.node = node
  state.base = node.modifiedAt
  state.dirty = false
  hideConflict()
  fillEditor()
}

// ───────────────────────── 轮询 ─────────────────────────

function startPolling() {
  clearInterval(state.pollTimer)
  state.pollTimer = setInterval(poll, 5000)
}

async function poll() {
  // 有本地未保存的改动时先不动，免得把用户正在敲的东西冲掉
  if (state.saving) return
  const { data } = await api('/api/books')
  if (!data.ok) {
    setConn(data.error || '连不上')
    return
  }
  state.books = data.books
  renderBooks()
  setConn('已连接 · ' + state.books.length + ' 本可编辑')

  if (!state.bookId) {
    if (state.books.length) openBook(state.books[0].id)
    return
  }
  if (state.dirty) return

  const { data: snapshot } = await api('/api/book', { id: state.bookId })
  if (!snapshot.ok) {
    state.bookId = null
    state.book = null
    state.tree = []
    state.nodeId = null
    state.node = null
    renderTree()
    renderBookHead()
    showEditor(false)
    toast('这本书被关掉共享或删除了')
    return
  }
  state.book = snapshot.book
  state.tree = snapshot.tree
  renderBookHead()
  renderTree()

  const row = state.tree.find((item) => item.id === state.nodeId)
  if (row && state.node && row.modifiedAt > state.node.modifiedAt) {
    toast('这一节在手机或被别的窗口改过了，重新点一下可以看到最新内容')
  }
}

function setConn(text) {
  $('conn-state').textContent = text
}

// ───────────────────────── 杂项 ─────────────────────────

let toastTimer = null

function toast(message) {
  const el = $('toast')
  el.textContent = message
  el.classList.remove('hidden')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => el.classList.add('hidden'), 3500)
}

function bindEvents() {
  $('gate-go').addEventListener('click', submitGate)
  $('gate-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') submitGate()
  })

  $('search').addEventListener('input', renderBooks)

  $('btn-new-group').addEventListener('click', () => createNode('group'))
  $('btn-new-chapter').addEventListener('click', () => createNode('chapter'))

  $('node-title').addEventListener('input', markDirty)
  $('node-summary').addEventListener('input', markDirty)
  $('node-content').addEventListener('input', () => {
    updateChars()
    markDirty()
  })

  $('conflict-load').addEventListener('click', loadServerVersion)
  $('conflict-force').addEventListener('click', () => {
    hideConflict()
    save(true)
  })

  document.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      save(true)
    }
  })

  window.addEventListener('beforeunload', (event) => {
    if (!state.dirty) return
    event.preventDefault()
    event.returnValue = ''
  })
}

boot()
