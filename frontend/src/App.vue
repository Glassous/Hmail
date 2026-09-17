<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import Icon from './Icon.vue'
import BrandIcon from './BrandIcon.vue'
import MailboxPicker from './MailboxPicker.vue'
import ComposeDialog from './ComposeDialog.vue'
import { api, ApiError, setCsrf } from './api'

type Account = {id:string; email:string; provider:string; status:string; isDefault?:boolean}
type MailServer = {host:string; port:number; security:'ssl'|'starttls'}
type MailPreset = {id:string; name:string; domains:string[]; imap:MailServer; smtp:MailServer; hint:string}
type Attachment = {id:string; name:string; size:number; messageId?:string}
type Mail = {id:string; threadId:string; draftId?:string; from:string; to:string; cc:string; bcc:string; subject:string; snippet:string; date:string; labels:string[]; count?:number; text:string; html:string; messageId:string; references:string; attachments:Attachment[]; accountId?:string; account?:string}
type Label = {id:string; name:string; type:string}
const user = ref<any>(null), booting = ref(true), config = ref({oauthEnabled:false, emailCodeEnabled:false, maxAttachmentBytes:18874368, mailProviders:[] as MailPreset[]})
const authMode = ref('login'), authBusy = ref(false), auth = reactive({email:'', password:'', code:''}), codeBusy = ref(false), codeSent = ref(false), codeCooldown = ref(0)
const accounts = ref<Account[]>([]), activeId = ref(''), folder = ref('INBOX'), query = ref(''), appliedQuery = ref(''), labels = ref<Label[]>([])
const items = ref<Mail[]>([]), messages = ref<Mail[]>([]), selected = ref<string[]>([]), activeThread = ref(''), nextCursor = ref(''), cursors = ref(['']), pageIndex = ref(0)
// 「全部账户」用它作为账户哨兵值；当前打开会话所属账户单独记录，供会话内接口使用。
const ALL_ACCOUNTS = '__all__'
const threadAccount = ref('')
const loading = ref(false), reading = ref(false), syncing = ref(false), sidebar = ref(false), modal = ref(''), error = ref(''), notice = ref(''), theme = ref<'system'|'light'|'dark'>((localStorage.getItem('hmail-theme') as 'system'|'light'|'dark') || 'system')
const connectionBusy = ref(false), changingPassword = reactive({currentPassword:'',password:''})
const connection = reactive({email:'',password:'',provider:'',imapHost:'',imapPort:993,imapSecurity:'ssl',smtpHost:'',smtpPort:465,smtpSecurity:'ssl'})
const connectMode = ref<'choose'|'imap'>('choose')
const labelName = ref(''), labelEdit = ref<Label|null>(null), labelChoice = ref(''), actionBusy = ref(false)
const composer = ref(false), composeAccount = ref(''), composingBusy = ref(false), uploading = ref(false), savedState = ref(''), composeDirty = ref(false), sendUncertain = ref(false)
const freshCompose = () => ({to:'',cc:'',bcc:'',subject:'',text:'',inReplyTo:'',references:'',threadId:null as string|null,draftId:null as string|null,composeId:crypto.randomUUID(),version:0,attachments:[] as Attachment[]})
const draft = reactive(freshCompose())
const composeStashed = ref(false)
const composeVisible = computed(() => !!user.value && composer.value && !composeStashed.value)
function restoreCompose() { sidebar.value=false; composeStashed.value=false }
function stashCompose() {
  composeStashed.value=true
  if(!uploading.value && !composingBusy.value)void saveDraft()
}
let saveTimer: ReturnType<typeof setTimeout> | undefined, toastTimer: ReturnType<typeof setTimeout> | undefined, codeTimer: ReturnType<typeof setInterval> | undefined
let savePromise: Promise<void> | null = null, loadGeneration = 0, readGeneration = 0
let listAbort: AbortController | undefined, readAbort: AbortController | undefined
let syncTimer: ReturnType<typeof setTimeout> | undefined, syncGeneration = 0, sendCheckTimer: ReturnType<typeof setTimeout> | undefined
const indexVersion = ref(0)
const pendingActions = reactive(new Set<string>())
const mutationVersions = new Map<string, number>()
const aborted = (e:unknown) => e instanceof DOMException && e.name==='AbortError'
const activeAccount = computed(() => accounts.value.find(a => a.id === activeId.value))
const isAll = computed(() => activeId.value === ALL_ACCOUNTS)
const allAccountsReady = computed(() => accounts.value.length >= 2)
const defaultAccount = computed(() => accounts.value.find(a => a.isDefault) || accounts.value[0])
const reconnectAccounts = computed(() => accounts.value.filter(a => a.status === 'reconnect'))
// 邮箱选择器额外展示一个伪账户「全部账户」；真实账户列表保持原样，避免哨兵值外溢。
const pickerAccounts = computed(() => allAccountsReady.value ? [{id:ALL_ACCOUNTS, email:'全部账户'}, ...accounts.value] : accounts.value)
// 统一视图里 id/threadId 可能跨账户重复，所有列表 key、选中集合与乐观更新都以「账户 + 标识」为身份。
const identity = (item:Mail) => `${item.accountId || activeId.value}:${item.id}`
const threadIdentity = (item:Mail) => `${item.accountId || activeId.value}:${item.threadId}`
const accountEmail = (aid?:string) => aid ? accounts.value.find(a => a.id === aid)?.email || '' : ''
const mailAccount = (item:Mail) => item.accountId || (isAll.value ? threadAccount.value || activeId.value : activeId.value)
const threadAid = computed(() => threadAccount.value || activeId.value)
const customLabels = computed(() => labels.value.filter(l => l.type === 'user'))
const presetHint = computed(() => config.value.mailProviders.find(p => p.id === connection.provider)?.hint || (connection.provider === 'custom' ? '请向邮箱服务商确认 IMAP 与 SMTP 服务器地址、端口与加密方式。' : ''))
const nav = [{id:'INBOX',name:'收件箱',icon:'inbox'},{id:'STARRED',name:'已加星标',icon:'star'},{id:'SENT',name:'已发送',icon:'send'},{id:'DRAFT',name:'草稿',icon:'edit'},{id:'ALL',name:'所有邮件',icon:'mail'},{id:'SPAM',name:'垃圾邮件',icon:'alert'},{id:'TRASH',name:'回收站',icon:'trash'}]
const folderTitle = computed(() => nav.find(n=>n.id===folder.value)?.name || labels.value.find(l=>l.id===folder.value)?.name || '邮件')
const allChecked = computed(() => items.value.length > 0 && selected.value.length === items.value.length)
const initials = (value:string) => (value.replace(/["<>]/g,'').trim()[0] || 'F').toUpperCase()
const senderName = (value:string) => value.split('<')[0].replace(/"/g,'').trim() || value
const shortDate = (value:string) => { const d = new Date(value); return isNaN(d.getTime()) ? '' : d.toLocaleDateString('zh-CN',{month:'short',day:'numeric'}) }
const sizeText = (value:number) => value > 1048576 ? (value/1048576).toFixed(1)+' MB' : Math.max(1,Math.round(value/1024))+' KB'
function toast(text:string) { notice.value = text; clearTimeout(toastTimer); toastTimer = setTimeout(()=>notice.value='',6000) }
function fail(e:unknown) { error.value = e instanceof Error ? e.message : '操作失败，请稍后重试'; if(e instanceof ApiError && e.code === 'unauthorized') { user.value = null; setCsrf('') } }
function path(suffix:string, aid=activeId.value) { return `/gmail-accounts/${encodeURIComponent(aid)}${suffix}` }
const systemMedia = typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null
function applyTheme() {
  const isDark = theme.value === 'dark' || (theme.value === 'system' && (systemMedia?.matches ?? false))
  document.documentElement.classList.toggle('dark', isDark)
  document.querySelectorAll('iframe').forEach(frame => {
    try { if(frame.contentDocument) styleMailScrollbars(frame.contentDocument) } catch {}
  })
}
watch(theme, value => {
  localStorage.setItem('hmail-theme', value)
  applyTheme()
}, { immediate: true })
async function setTheme(value: 'system' | 'light' | 'dark') {
  theme.value = value
  if (user.value) try { await api('/me', 'PATCH', { theme: value }) } catch(e) { fail(e) }
}
async function toggleTheme() {
  const next = theme.value === 'system' ? 'light' : theme.value === 'light' ? 'dark' : 'system'
  await setTheme(next)
}

async function submitAuth() {
  authBusy.value=true; error.value=''
  try {
    if(authMode.value==='login') {
      const result=await api('/auth/login','POST',{email:auth.email,password:auth.password})
      user.value=result.user; setCsrf(result.csrf); theme.value=result.user.theme; auth.password=''; await loadAccounts(); return
    }
    const result=await api('/auth/'+authMode.value,'POST',{email:auth.email,password:auth.password,code:auth.code})
    if(authMode.value==='register') { user.value=result.user; setCsrf(result.csrf); theme.value=result.user.theme; auth.password=''; auth.code=''; await loadAccounts(); return }
    switchAuth('login'); toast('密码已重设，请使用新密码登录')
  } catch(e) { fail(e) } finally { authBusy.value=false }
}
function startCooldown(seconds:number) {
  codeCooldown.value=seconds; clearInterval(codeTimer)
  codeTimer=setInterval(()=>{ codeCooldown.value--; if(codeCooldown.value<=0) clearInterval(codeTimer) },1000)
}
async function sendCode() {
  if(!auth.email) { error.value='请先填写邮箱地址'; return }
  codeBusy.value=true; error.value=''
  try {
    const result=await api('/auth/send-code','POST',{email:auth.email,purpose:authMode.value==='register'?'register':'reset'})
    codeSent.value=true; toast('验证码已发送，请查收邮件'); startCooldown(result.cooldown||60)
  } catch(e) { fail(e) } finally { codeBusy.value=false }
}
function switchAuth(mode:string) { authMode.value=mode; auth.password=''; auth.code=''; codeSent.value=false; codeCooldown.value=0; clearInterval(codeTimer); error.value='' }
async function logout() { try { await closeCompose(); if(composer.value)return; await api('/auth/logout','POST'); user.value=null; accounts.value=[]; activeId.value=''; modal.value=''; setCsrf('') } catch(e){fail(e)} }
async function loadAccounts() {
  accounts.value=await api('/gmail-accounts')
  if(activeId.value===ALL_ACCOUNTS) {
    // 连接数不足两家时「全部账户」没有意义，回落到默认邮箱。
    if(accounts.value.length<2) activeId.value=accounts.value.find(a=>a.isDefault)?.id||accounts.value[0]?.id||''
    return
  }
  if(!accounts.value.some(a=>a.id===activeId.value)) activeId.value=accounts.value.find(a=>a.isDefault)?.id||accounts.value[0]?.id||''
}
watch(activeId, async () => {
  listAbort?.abort(); readAbort?.abort(); ++loadGeneration; ++readGeneration; ++syncGeneration; clearTimeout(syncTimer)
  reading.value=false; syncing.value=false; folder.value='INBOX'; query.value=''; appliedQuery.value=''; activeThread.value=''; threadAccount.value=''; messages.value=[]; labels.value=[]; items.value=[]; resetPages()
  const aid=activeId.value
  if(aid) {
    await Promise.allSettled([loadList(), loadLabels(aid)])
    if(aid===activeId.value)void refresh(false)
  }
})
async function loadLabels(aid=activeId.value) { if(aid===ALL_ACCOUNTS){labels.value=[];return} try { const result=await api<Label[]>(path('/labels',aid)); if(aid===activeId.value)labels.value=result }catch(e){if(aid===activeId.value)fail(e)} }
function resetPages() { cursors.value=['']; pageIndex.value=0; nextCursor.value=''; selected.value=[] }
async function loadList() {
  if(!activeId.value)return
  listAbort?.abort(); listAbort=new AbortController()
  const generation=++loadGeneration, aid=activeId.value, all=isAll.value, chosenFolder=folder.value; loading.value=true
  try { const result=all?await api('/threads?'+new URLSearchParams({folder:chosenFolder,cursor:cursors.value[pageIndex.value]||''}),'GET',undefined,listAbort.signal):await api(path('/threads',aid)+'?'+new URLSearchParams({folder:chosenFolder,q:appliedQuery.value,cursor:cursors.value[pageIndex.value]||''}),'GET',undefined,listAbort.signal); if(generation!==loadGeneration||aid!==activeId.value||all!==isAll.value||chosenFolder!==folder.value)return; items.value=result.items.map((item:Mail)=>pendingActions.has(identity(item))?items.value.find(old=>identity(old)===identity(item))||item:item); nextCursor.value=result.nextCursor; selected.value=selected.value.filter(key=>items.value.some(item=>identity(item)===key)); if(result.sync)indexVersion.value=result.sync.indexVersion }
  catch(e){if(generation===loadGeneration&&!aborted(e)){if(e instanceof ApiError&&e.code==='cursor_expired'){resetPages();void loadList()}else fail(e)}} finally{if(generation===loadGeneration)loading.value=false}
}
async function chooseFolder(id:string) { readAbort?.abort();++readGeneration;reading.value=false;folder.value=id; activeThread.value=''; threadAccount.value=''; messages.value=[]; sidebar.value=false; resetPages(); await loadList();void refresh(false) }
async function search() { if(isAll.value)return; readAbort?.abort();++readGeneration;reading.value=false;appliedQuery.value=query.value.trim(); activeThread.value=''; threadAccount.value=''; resetPages(); await loadList() }
async function paginate(direction:number) { if(direction>0){cursors.value[pageIndex.value+1]=nextCursor.value;pageIndex.value++}else pageIndex.value--; await loadList() }
async function refresh(manual=true) {
  const aid=activeId.value, all=isAll.value, chosenFolder=folder.value, generation=++syncGeneration
  if(!aid)return
  clearTimeout(syncTimer);syncing.value=true
  const current=()=>generation===syncGeneration&&aid===activeId.value&&all===isAll.value&&chosenFolder===folder.value
  const poll=async()=>{
    if(!current())return
    if(document.hidden){syncTimer=setTimeout(poll,2000);return}
    try {
      const status=all?await api('/threads/sync-status?folder='+encodeURIComponent(chosenFolder)):await api(path('/sync-status',aid)+'?folder='+encodeURIComponent(chosenFolder))
      if(!current())return
      if(status.indexVersion!==indexVersion.value){resetPages();await Promise.allSettled(all?[loadList()]:[loadList(),loadLabels(aid)])}
      if(!current())return
      syncing.value=['queued','running','retry'].includes(status.status)
      if(status.status==='failed'){syncing.value=false;if(manual)toast('同步暂时失败，已有邮件仍可阅读')}
      if(status.status==='completed'&&manual){toast(all?'全部邮箱已刷新':'邮箱已刷新');manual=false}
      syncTimer=setTimeout(poll,syncing.value?2000:30000)
    }catch(e){if(current()){syncing.value=false;if(manual)fail(e);syncTimer=setTimeout(poll,30000)}}
  }
  try { if(all)await api('/sync-all','POST');else await api(path('/sync-jobs',aid)+'?folder='+encodeURIComponent(chosenFolder),'POST');if(current())void poll() }
  catch(e){if(current()){syncing.value=false;if(manual)fail(e)}}
}
async function openMail(item:Mail) {
  if(folder.value==='DRAFT') { await openDraft(item); return }
  readAbort?.abort();readAbort=new AbortController()
  const generation=++readGeneration,aid=mailAccount(item),tid=item.threadId
  threadAccount.value=aid; activeThread.value=tid; reading.value=true; messages.value=[]
  try { const data=await api<Mail[]>(path('/threads/'+encodeURIComponent(tid),aid),'GET',undefined,readAbort.signal); if(generation!==readGeneration||threadAccount.value!==aid||activeThread.value!==tid)return; messages.value=data; reading.value=false; if(data.some(m=>m.labels.includes('UNREAD')))void modify([],['UNREAD'],'labels',data.map(m=>m.id),aid) }
  catch(e){if(generation===readGeneration&&!aborted(e))fail(e)}finally{if(generation===readGeneration)reading.value=false}
}
/** 统一视图的批量操作：按邮件所属账户分组，对各自账户接口分别提交，再合并失败项统一回滚。 */
async function modify(add:string[]=[],remove:string[]=[],action='labels',ids?:string[],explicitAid?:string) {
  const context=activeId.value,chosenFolder=folder.value,thread=activeThread.value
  const groups=new Map<string,{ids:string[];threads:string[]}>()
  const push=(aid:string,kind:'ids'|'threads',values:string[])=>{if(!values.length)return;const group=groups.get(aid)||{ids:[],threads:[]};group[kind].push(...values);groups.set(aid,group)}
  if(ids?.length) push(explicitAid||threadAccount.value||context,'ids',ids)
  else if(thread) push(threadAccount.value||explicitAid||context,'ids',messages.value.map(m=>m.id))
  else for(const item of items.value.filter(m=>selected.value.includes(identity(m)))) push(mailAccount(item),'threads',[item.threadId])
  const entries=[...groups]
  const keys=entries.flatMap(([aid,group])=>[...group.ids,...group.threads].map(value=>`${aid}:${value}`))
  if(!keys.length)return
  if(keys.some(key=>pendingActions.has(key)))return
  const versions=new Map<string,number>()
  for(const key of keys){pendingActions.add(key);const v=(mutationVersions.get(key)||0)+1;mutationVersions.set(key,v);versions.set(key,v)}
  const snapshots=new Map<Mail,string[]>()
  const owns=(item:Mail)=>entries.some(([aid,group])=>mailAccount(item)===aid&&(group.ids.includes(item.id)||group.threads.includes(item.threadId)))
  for(const item of [...items.value,...messages.value])if(owns(item)){snapshots.set(item,[...item.labels]);item.labels=[...new Set([...item.labels.filter(l=>!remove.includes(l)),...add])]}
  const failed=new Set<string>()
  try {
    const outcomes=await Promise.allSettled(entries.map(([aid,group])=>api(path('/messages/modify',aid),'POST',{ids:group.threads.length?[]:group.ids,threadIds:group.threads,add,remove,action})))
    if(context!==activeId.value||chosenFolder!==folder.value)return
    let unauthorized:unknown=null
    outcomes.forEach((outcome,index)=>{
      const [aid,group]=entries[index]
      if(outcome.status==='fulfilled'){ for(const row of (outcome.value?.results||[])) if(!row.ok) failed.add(`${aid}:${row.id}`) }
      else { for(const value of [...group.ids,...group.threads]) failed.add(`${aid}:${value}`); if(outcome.reason instanceof ApiError&&outcome.reason.code==='unauthorized')unauthorized=outcome.reason }
    })
    const isFailed=(item:Mail)=>failed.has(`${mailAccount(item)}:${item.id}`)||failed.has(`${mailAccount(item)}:${item.threadId}`)
    for(const [item,old]of snapshots)if(isFailed(item))item.labels=old
    if(failed.size)toast('部分邮件未能更新，请重试失败项')
    if(action!=='labels'||remove.includes(chosenFolder)){items.value=items.value.filter(item=>!snapshots.has(item)||isFailed(item));if(thread&&thread===activeThread.value&&!failed.size){activeThread.value='';threadAccount.value='';messages.value=[]}}
    if(unauthorized)throw unauthorized
  }catch(e){if(context===activeId.value){for(const [item,old]of snapshots)item.labels=old;fail(e)}}
  finally{for(const [key,v]of versions)if(mutationVersions.get(key)===v)pendingActions.delete(key)}
}
async function star(item:Mail) { await modify(item.labels.includes('STARRED')?[]:['STARRED'],item.labels.includes('STARRED')?['STARRED']:[],'labels',[item.id],mailAccount(item)) }
function checkAll() { selected.value=allChecked.value?[]:items.value.map(m=>identity(m)) }
async function allowImages(message:Mail) { try{const result=await api(path('/messages/'+encodeURIComponent(message.id),threadAid.value)+'?remote=true'); message.html=result.html;toast('已通过安全代理重新加载图片')}catch(e){fail(e)} }
function onIframeLoad(e: Event) {
  const iframe = e.target as HTMLIFrameElement
  if (!iframe) return
  try {
    const doc = iframe.contentDocument || iframe.contentWindow?.document
    if (doc?.documentElement) {
      styleMailScrollbars(doc)
      const resize = () => {
        const height = Math.max(doc.body?.scrollHeight || 0, doc.documentElement.scrollHeight || 0)
        if (height > 0) iframe.style.height = `${height + 24}px`
      }
      resize()
      if (typeof window !== 'undefined' && 'ResizeObserver' in window && doc.body) {
        new ResizeObserver(resize).observe(doc.body)
      }
    }
  } catch {}
}

function styleMailScrollbars(doc: Document) {
  const dark = document.documentElement.classList.contains('dark')
  let style = doc.getElementById('hmail-scrollbars') as HTMLStyleElement | null
  if(!style) { style=doc.createElement('style');style.id='hmail-scrollbars';(doc.head || doc.documentElement).append(style) }
  style.textContent=`:root{--scroll-thumb:${dark?'#52647d':'#bcc9dc'};--scroll-hover:${dark?'#7992b2':'#889fbf'};--scroll-track:${dark?'#19212e':'#f1f5fa'}}*{scrollbar-width:thin;scrollbar-color:var(--scroll-thumb) var(--scroll-track)}::-webkit-scrollbar{width:8px;height:8px}::-webkit-scrollbar-track,::-webkit-scrollbar-corner{background:var(--scroll-track)}::-webkit-scrollbar-thumb{background:var(--scroll-thumb);border:2px solid var(--scroll-track);border-radius:99px}::-webkit-scrollbar-thumb:hover{background:var(--scroll-hover)}`
}

async function oauthConnect() { connectionBusy.value=true;try{const result=await api('/gmail-accounts/oauth/start','POST');location.assign(result.url)}catch(e){fail(e);connectionBusy.value=false} }
function applyPreset(id:string) {
  connection.provider=id
  const preset=config.value.mailProviders.find(p=>p.id===id)
  if(!preset){connection.imapHost='';connection.smtpHost='';connection.imapPort=993;connection.smtpPort=465;connection.imapSecurity='ssl';connection.smtpSecurity='ssl';return}
  connection.imapHost=preset.imap.host;connection.imapPort=preset.imap.port;connection.imapSecurity=preset.imap.security
  connection.smtpHost=preset.smtp.host;connection.smtpPort=preset.smtp.port;connection.smtpSecurity=preset.smtp.security
}
function detectProvider() {
  if(connection.provider)return
  const domain=(connection.email.split('@')[1]||'').trim().toLowerCase()
  if(!domain)return
  const preset=config.value.mailProviders.find(p=>p.domains.includes(domain))
  if(preset)applyPreset(preset.id)
}
function openConnect() { modal.value='connect';connectMode.value='choose';connection.password='';connection.provider='';connection.imapHost='';connection.smtpHost='';connection.imapPort=993;connection.smtpPort=465;connection.imapSecurity='ssl';connection.smtpSecurity='ssl';detectProvider() }
async function imapConnect() {
  connectionBusy.value=true
  try {
    const account=await api('/gmail-accounts/imap','POST',{email:connection.email,password:connection.password,imapHost:connection.imapHost,imapPort:connection.imapPort,imapSecurity:connection.imapSecurity,smtpHost:connection.smtpHost,smtpPort:connection.smtpPort,smtpSecurity:connection.smtpSecurity})
    connection.password='';modal.value='';await loadAccounts();activeId.value=account.id;toast('邮箱已连接')
  } catch(e){fail(e)} finally{connectionBusy.value=false}
}
async function setDefaultAccount(account:Account) {
  try {
    await api('/me/default-account','PUT',{accountId:account.isDefault?'':account.id})
    await loadAccounts()
    toast(account.isDefault?'已取消默认邮箱':'已将 '+account.email+' 设为默认邮箱')
  } catch(e){fail(e)}
}
async function disconnect(account:Account) { if(!confirm(`断开 ${account.email}？邮箱中的邮件将保留。`))return;try{await api('/gmail-accounts/'+account.id,'DELETE');await loadAccounts();toast('已断开连接')}catch(e){fail(e)} }
async function changePassword() { try{await api('/me/password','POST',{currentPassword:changingPassword.currentPassword,password:changingPassword.password});changingPassword.currentPassword='';changingPassword.password='';user.value=null;modal.value='';setCsrf('');toast('密码已修改，请重新登录')}catch(e){fail(e)} }
async function saveLabel() { try{await api(path('/labels'),'POST',{action:labelEdit.value?'rename':'create',id:labelEdit.value?.id||'',name:labelName.value});labels.value=await api(path('/labels'));modal.value='';toast('标签已保存')}catch(e){fail(e)} }
async function deleteLabel(label:Label) { if(!confirm(`删除标签“${label.name}”？邮件仍保留。`))return;try{await api(path('/labels'),'POST',{action:'delete',id:label.id});labels.value=await api(path('/labels'));if(folder.value===label.id)await chooseFolder('INBOX')}catch(e){fail(e)} }

async function newCompose(mode='', message?:Mail) {
  if(composer.value){restoreCompose();if(mode || message)toast('请先保存并关闭当前写信窗口');return}
  // 统一视图没有单一发件账户：新邮件用默认邮箱，回复时用该邮件所属邮箱。
  const account=message?.accountId||(isAll.value?defaultAccount.value?.id||'':activeId.value)
  if(!account){openConnect();return}
  Object.assign(draft,freshCompose()); composeAccount.value=account; savedState.value='';sendUncertain.value=false
  if(message){
    draft.subject=/^(re|fwd):/i.test(message.subject)?message.subject:(mode==='forward'?'Fwd: ':'Re: ')+message.subject
    if(mode!=='forward') {
      const address=(value:string)=>value.match(/<([^>]+)>/)?.[1]||value
      draft.to=address(message.from)
      if(mode==='replyAll'){ const own=accounts.value.find(a=>a.id===composeAccount.value)?.email.toLowerCase(); const list=[...message.to.split(','),...message.cc.split(',')].map(address).map(a=>a.trim()).filter(a=>a&&a.toLowerCase()!==own&&a.toLowerCase()!==draft.to.toLowerCase());draft.cc=[...new Set(list)].join(', ') }
      draft.inReplyTo=message.messageId;draft.references=(message.references+' '+message.messageId).trim();draft.threadId=message.threadId
    } else draft.attachments=message.attachments.map(a=>({...a,messageId:message.id}))
    draft.text='\n\n'+(mode==='forward'?'---------- 转发邮件 ----------':'在 '+message.date+'，'+message.from+' 写道：')+'\n'+message.text.split('\n').map(l=>'> '+l).join('\n')
  }
  restoreCompose();composer.value=true;composeDirty.value=false
  if(message)dirty()
}
async function openDraft(item:Mail) {
  if(composer.value){restoreCompose();toast('请先保存并关闭当前写信窗口');return}
  reading.value=true
  try{
    const context=activeId.value,aid=item.accountId||(isAll.value?defaultAccount.value?.id||'':activeId.value),generation=++readGeneration
    if(!aid)throw new Error('未找到草稿所属邮箱，请刷新后重试')
    const list=item.draftId?[]:await api<any[]>(path('/drafts',aid)); let found=item.draftId?{id:item.draftId}:list.find(d=>d.messageId===item.id||d.id===item.id)
    if(!found){for(const d of list){const content=await api<Mail>(path('/drafts/'+encodeURIComponent(d.id),aid));if(content.threadId===item.threadId){found=d;break}}}
    if(!found)throw new Error('未找到草稿，请刷新后重试')
    const mail=await api<Mail>(path('/drafts/'+encodeURIComponent(found.id),aid))
    if(context!==activeId.value||generation!==readGeneration)return
    await newCompose();composeAccount.value=aid;Object.assign(draft,{to:mail.to,cc:mail.cc,bcc:mail.bcc,subject:mail.subject==='(无主题)'?'':mail.subject,text:mail.text,draftId:found.id,threadId:mail.threadId,references:mail.references,attachments:mail.attachments.map(a=>({...a,messageId:mail.id}))});composeDirty.value=false;savedState.value='已载入草稿'
  }catch(e){fail(e)}finally{reading.value=false}
}
function dirty() { composeDirty.value=true;savedState.value='尚未保存';clearTimeout(saveTimer);if(!sendUncertain.value)saveTimer=setTimeout(()=>{void saveDraft()},2000) }
async function saveDraft() {
  if(!composer.value||!composeDirty.value||sendUncertain.value||uploading.value)return
  if(savePromise){await savePromise;if(composeDirty.value)return saveDraft();return}
  composingBusy.value=true;composeDirty.value=false;savedState.value='正在保存…';draft.version++
  const payload=JSON.parse(JSON.stringify(draft));const aid=composeAccount.value
  savePromise=(async()=>{try{const result=await api(path('/drafts',aid),'PUT',payload);draft.draftId=result.id;draft.attachments=draft.attachments.map(a=>{const index=payload.attachments.findIndex((old:Attachment)=>old.id===a.id&&old.messageId===a.messageId);return index>=0&&result.attachments?.[index]?result.attachments[index]:a});savedState.value='已保存至邮箱';}catch(e){composeDirty.value=true;savedState.value='保存失败，请重试';fail(e)}finally{composingBusy.value=false}})()
  await savePromise;savePromise=null
}
async function closeCompose() { if(uploading.value||composingBusy.value)return;clearTimeout(saveTimer);if(savePromise)await savePromise; if(composeDirty.value&&!sendUncertain.value)await saveDraft();if(composeDirty.value&&!sendUncertain.value){toast('草稿未保存，请重试后关闭');return}composer.value=false;composeStashed.value=false;await loadList() }
async function discardCompose() { if(uploading.value||composingBusy.value)return;if(!confirm('丢弃这封草稿？'))return;clearTimeout(saveTimer);if(savePromise)await savePromise;try{if(draft.draftId)await api(path('/drafts/'+encodeURIComponent(draft.draftId),composeAccount.value),'DELETE');composer.value=false;composeStashed.value=false;composeDirty.value=false;await loadList()}catch(e){fail(e)} }
async function uploadFiles(event:Event) { const input=event.target as HTMLInputElement;const files=Array.from(input.files||[]);if(!files.length)return;uploading.value=true;clearTimeout(saveTimer);try{for(const file of files){if(draft.attachments.reduce((sum,a)=>sum+a.size,0)+file.size>config.value.maxAttachmentBytes)throw new Error('附件总大小不能超过 18 MiB');const form=new FormData();form.append('file',file);draft.attachments.push(await api(path('/attachments',composeAccount.value),'POST',form))}dirty()}catch(e){fail(e)}finally{uploading.value=false;input.value=''} }
async function sendMail() {
  if(composingBusy.value||uploading.value||sendUncertain.value)return
  clearTimeout(saveTimer);if(savePromise)await savePromise;composingBusy.value=true
  try{const result=await api(path('/send',composeAccount.value),'POST',draft);composer.value=false;composeDirty.value=false;toast(result.warning||(result.result?.refused?.length?'邮件已发送，但部分收件人被拒绝：'+result.result.refused.join(', '):'邮件已发送'));await loadList();void refresh(false)}
  catch(e){if(e instanceof ApiError&&e.code==='send_uncertain'){sendUncertain.value=true;savedState.value='发送结果待确认，正在自动核对';void verifySend()}fail(e)}finally{composingBusy.value=false}
}
async function verifySend(attempt=0) {
  if(!sendUncertain.value||attempt>5)return
  const aid=composeAccount.value,cid=draft.composeId
  try {
    const state=await api<{status:string;result?:any}>(path('/send-status',aid)+'?composeId='+encodeURIComponent(cid))
    if(!sendUncertain.value||composeAccount.value!==aid||draft.composeId!==cid)return
    if(state.status==='sent'){sendUncertain.value=false;composer.value=false;composeDirty.value=false;error.value='';toast(state.result?.warning||'邮件已确认发送成功');await loadList();void refresh(false);return}
    if(state.status!=='pending'){sendUncertain.value=false;savedState.value=state.status==='none'?'未发送，可重新编辑后发送':'可直接继续编辑';return}
  } catch {}
  sendCheckTimer=setTimeout(()=>void verifySend(attempt+1),4000)
}
function beforeUnload(e:BeforeUnloadEvent) {if(composer.value&&(composeDirty.value||composingBusy.value||uploading.value)){e.preventDefault();e.returnValue=''}}
onMounted(async()=>{
  systemMedia?.addEventListener('change', applyTheme)
  window.addEventListener('beforeunload', beforeUnload)
  try {
    config.value = await api('/config')
    try {
      const session = await api('/me')
      user.value = session.user
      setCsrf(session.csrf)
      if (session.user.theme) theme.value = session.user.theme
      await loadAccounts()
    } catch(e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e
    }
    const result = new URLSearchParams(location.search).get('connection')
    if (result) {
      toast(result === 'success' ? 'Google 授权成功' : '已取消 Google 授权')
      history.replaceState({}, '', location.pathname)
    }
  } catch(e) {
    fail(e)
  } finally {
    booting.value = false
  }
})
onUnmounted(()=>{
  clearTimeout(saveTimer)
  clearTimeout(toastTimer)
  clearInterval(codeTimer)
  clearTimeout(syncTimer);clearTimeout(sendCheckTimer);listAbort?.abort();readAbort?.abort();++syncGeneration
  systemMedia?.removeEventListener('change', applyTheme)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div v-if="booting" class="flex min-h-screen items-center justify-center gap-3 text-slate-500"><Icon name="refresh" class="animate-spin"/>正在打开 Hmail…</div>
  <div v-else-if="!user" class="relative flex min-h-screen items-center justify-center p-5 lg:p-12">
    <button class="icon-btn absolute right-6 top-6" v-tip="theme==='system'?'跟随系统':theme==='light'?'浅色模式':'深色模式'" :aria-label="'切换主题，当前：'+(theme==='system'?'跟随系统':theme==='light'?'浅色模式':'深色模式')" @click="toggleTheme"><Icon :name="theme==='system'?'system':theme==='dark'?'moon':'sun'"/></button>
    <div class="grid w-full max-w-5xl overflow-hidden rounded-[32px] border divider surface shadow-xl shadow-slate-200/40 dark:shadow-none lg:grid-cols-2">
      <section class="relative hidden flex-col justify-between overflow-hidden bg-[#e8f0fe] p-12 text-slate-800 lg:flex">
        <div class="flex items-center gap-3"><BrandIcon :size="44"/><span class="text-2xl font-semibold tracking-tight">Hmail<span class="text-blue-600">.</span></span></div>
        <div class="py-20"><div class="mb-5 text-xs font-semibold uppercase tracking-[.3em] text-blue-600">A little more organized</div><h1 class="text-4xl font-semibold leading-snug tracking-tight">让邮件归位，<br/>让思绪留白。</h1><p class="mt-5 max-w-xs text-sm leading-7 text-slate-500">连接你的邮箱，让每一次收发都井然有序。熟悉的邮箱，更专注的空间。</p>
        <div class="mt-10 rotate-[-3deg] rounded-2xl bg-white/90 p-5 shadow-xl shadow-blue-200/30"><div class="flex items-center gap-3"><span class="flex h-10 w-10 items-center justify-center rounded-full bg-blue-100 text-blue-600"><Icon name="inbox"/></span><div><div class="text-sm font-semibold">给重要的事，多一点空间</div><div class="mt-1 text-xs text-slate-400">收发 · 整理 · 专注</div></div><Icon name="check" class="ml-auto text-blue-500"/></div><div class="mt-5 h-2 w-4/5 rounded bg-slate-100"></div><div class="mt-3 h-2 w-3/5 rounded bg-slate-100"></div></div></div>
        <p class="flex items-center gap-2 text-xs text-slate-500"><Icon name="shield" :size="16"/>你的邮箱，由你掌控</p>
      </section>
      <section class="p-8 sm:p-12 lg:p-14">
        <div class="mb-12 flex items-center gap-2 text-xl font-semibold lg:hidden"><BrandIcon :size="32"/>Hmail.</div>
        <div class="mb-8"><p class="mb-3 text-xs font-medium tracking-widest text-blue-600">WELCOME TO HMAIL</p><h2 class="text-3xl font-semibold tracking-tight">{{authMode==='login'?'欢迎回来':authMode==='register'?'创建你的账户':'找回密码'}}</h2><p class="mt-3 text-sm text-slate-500">{{authMode==='login'?'登录后，即可连接你的邮箱。':authMode==='register'?'使用邮箱验证码注册，开启属于自己的邮件空间。':'通过注册邮箱接收验证码来重设密码。'}}</p></div>
        <form class="space-y-5" @submit.prevent="submitAuth">
          <label class="block text-sm">邮箱<input v-model="auth.email" required type="email" maxlength="254" autocomplete="email" class="field mt-2" placeholder="you@example.com"/></label>
          <label v-if="authMode==='login'" class="block text-sm">密码<input v-model="auth.password" required minlength="10" maxlength="128" type="password" autocomplete="current-password" class="field mt-2" placeholder="至少 10 个字符"/></label>
          <template v-else>
            <label class="block text-sm">邮箱验证码<span class="mt-2 flex gap-2"><input v-model="auth.code" required inputmode="numeric" pattern="\d{6}" minlength="6" maxlength="6" autocomplete="one-time-code" class="field flex-1" placeholder="6 位数字验证码"/><button type="button" class="secondary shrink-0 whitespace-nowrap" :disabled="codeBusy||codeCooldown>0" @click="sendCode"><Icon v-if="codeBusy" name="refresh" :size="14" class="animate-spin"/>{{codeCooldown>0?codeCooldown+' 秒后重发':'获取验证码'}}</button></span></label>
            <label class="block text-sm">{{authMode==='register'?'密码':'新密码'}}<input v-model="auth.password" required minlength="10" maxlength="128" type="password" autocomplete="new-password" class="field mt-2" placeholder="至少 10 个字符"/></label>
          </template>
          <p v-if="authMode!=='login'&&!config.emailCodeEnabled" class="text-xs leading-6 text-amber-600">邮件服务尚未配置，暂时无法发送验证码，请联系管理员。</p>
          <button v-if="authMode==='login'" type="button" class="block text-xs text-blue-600" @click="switchAuth('reset')">忘记密码？</button>
          <button class="primary w-full !py-3.5" :disabled="authBusy||(authMode!=='login'&&!config.emailCodeEnabled)"><Icon v-if="authBusy" name="refresh" :size="16" class="animate-spin"/>{{authMode==='login'?'登录':authMode==='register'?'创建账户':'重设密码'}}<Icon v-if="!authBusy" name="chevron" :size="16"/></button>
        </form>
        <p class="mt-8 text-center text-sm text-slate-500">{{authMode==='login'?'还没有账户？':'已有账户？'}} <button class="font-medium text-blue-600" @click="switchAuth(authMode==='login'?'register':'login')">{{authMode==='login'?'立即注册':'返回登录'}}</button></p>
        <p class="mt-10 text-center text-xs leading-6 text-slate-400">平台账户与你的邮箱账户独立。<br/>登录后选择 Google 授权或 IMAP＋SMTP 连接。</p>
      </section>
    </div>
  </div>

  <div v-else id="mail-workspace" class="flex h-dvh flex-col overflow-hidden">
    <header class="flex h-[76px] shrink-0 items-center gap-3 px-4 md:gap-5 md:px-6">
      <button class="icon-btn lg:hidden" :aria-label="sidebar?'收起侧栏':'打开侧栏'" v-tip="sidebar?'收起侧栏':'打开侧栏'" @click="sidebar=!sidebar"><Icon name="menu" :size="24"/></button>
      <a href="/" class="flex w-auto shrink-0 items-center gap-3 lg:w-[218px]" aria-label="Hmail 首页"><BrandIcon class="hidden lg:block"/><span class="hidden text-[23px] font-semibold tracking-tight sm:block">Hmail<span class="text-blue-600">.</span></span></a>
      <form v-if="!isAll" class="flex h-12 min-w-0 max-w-3xl flex-1 items-center rounded-full bg-[#eaf0fa] px-4 dark:bg-slate-800" @submit.prevent="search"><Icon name="search" class="shrink-0 text-slate-500"/><input v-model="query" :disabled="!activeId" class="w-full bg-transparent px-3 text-sm outline-none focus-visible:ring-0" placeholder="搜索邮件" aria-label="搜索邮件，支持 from:、to:、subject: 前缀"/><button v-if="query" type="button" class="text-slate-500" aria-label="清空搜索" v-tip="'清空搜索'" @click="query='';search()"><Icon name="close" :size="16"/></button></form>

    </header>
    <div class="flex min-h-0 flex-1 pb-3 pl-3 pr-3 lg:pl-0">
      <div v-if="sidebar" class="fixed inset-0 z-30 bg-slate-900/30 lg:hidden" @click="sidebar=false"></div>
      <aside class="fixed inset-y-0 left-0 z-40 flex w-[260px] shrink-0 flex-col bg-[#f6f8fc] px-4 pb-4 pt-6 transition-transform dark:bg-[#10151e] lg:static lg:translate-x-0 lg:pt-1" :class="sidebar?'translate-x-0':'-translate-x-full'">
        <a href="/" class="mb-5 flex shrink-0 items-center gap-3 px-2 lg:hidden" aria-label="Hmail 首页"><BrandIcon :size="40"/><span class="text-lg font-semibold tracking-tight">Hmail<span class="text-violet-500">.</span></span></a>
        <button class="mb-6 ml-1 flex shrink-0 w-fit items-center gap-4 rounded-2xl bg-[#c2e7ff] px-6 py-4 font-medium text-[#16394f] shadow-sm transition hover:shadow-md" @click="newCompose()"><Icon name="edit" :size="22"/>写邮件</button>
        <MailboxPicker v-model="activeId" :accounts="pickerAccounts" :hidden="!sidebar"/>
        <div class="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <nav class="space-y-1"><button v-for="n in nav" :key="n.id" class="flex w-full items-center gap-4 rounded-full px-5 py-2.5 text-sm transition" :class="folder===n.id?'bg-[#d3e3fd] font-semibold text-[#17365e] dark:bg-blue-900/50 dark:text-blue-200':'text-slate-600 hover:bg-slate-200/60 dark:text-slate-400 dark:hover:bg-slate-800'" :disabled="!activeId" @click="chooseFolder(n.id)"><Icon :name="n.icon" :size="19"/>{{n.name}}<span v-if="n.id==='INBOX'&&folder==='INBOX'&&items.filter(m=>m.labels.includes('UNREAD')).length" class="ml-auto text-xs">{{items.filter(m=>m.labels.includes('UNREAD')).length}}</span></button></nav>
        <div v-if="!isAll" class="mt-7 flex items-center justify-between px-5"><span class="text-xs font-medium text-slate-500">标签</span><button class="text-slate-500 hover:text-blue-600" aria-label="新建标签" v-tip="'新建标签'" :disabled="!activeId" @click="labelEdit=null;labelName='';modal='label'"><Icon name="plus" :size="17"/></button></div>
        <div v-if="!isAll" class="mt-2"><div v-for="label in customLabels" :key="label.id" class="group flex items-center rounded-full" :class="folder===label.id?'bg-blue-100 dark:bg-blue-900/30':''"><button class="flex min-w-0 flex-1 items-center gap-4 px-5 py-2.5 text-sm text-slate-500" @click="chooseFolder(label.id)"><Icon name="tag" :size="17" class="shrink-0"/><span class="truncate">{{label.name}}</span></button><button class="mr-2 text-slate-400 opacity-0 focus:opacity-100 group-hover:opacity-100" :aria-label="'编辑标签 '+label.name" v-tip="'编辑标签'" @click="labelEdit=label;labelName=label.name;modal='label'"><Icon name="edit" :size="14"/></button></div><p v-if="!customLabels.length" class="px-5 py-3 text-xs text-slate-400">用标签整理你的邮件</p></div>
        </div>
        <div class="mt-auto shrink-0 space-y-2 pt-4">
          <div class="space-y-1.5">
            <div class="flex items-center gap-2 pb-2">
              <button class="flex min-w-0 flex-1 items-center gap-2 rounded-xl px-2 py-2 text-xs font-medium text-slate-600 transition hover:bg-slate-200/60 dark:text-slate-300 dark:hover:bg-slate-800" :aria-label="user.email+' 的账户设置'" @click="modal='settings'">
                <span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#d3e3fd] text-sm font-semibold text-blue-800 dark:bg-blue-900/60 dark:text-blue-200">{{initials(user.email)}}</span>
                <span class="whitespace-nowrap">账户设置</span>
              </button>
              <button class="inline-flex shrink-0 items-center gap-1 rounded-xl bg-blue-600 px-3 py-3 text-xs font-medium text-white transition hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400" @click="openConnect"><Icon name="plus" :size="15"/>新连接</button>
            </div>
            <div class="flex items-center rounded-xl bg-slate-200/60 p-1 dark:bg-slate-800/70" role="radiogroup" aria-label="主题模式选择">
              <button type="button" class="flex flex-1 items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs transition" :class="theme==='system'?'bg-white font-medium text-blue-600 shadow-sm dark:bg-slate-700 dark:text-blue-300':'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'" :aria-checked="theme==='system'" role="radio" @click="setTheme('system')">
                <Icon name="system" :size="14"/><span>系统</span>
              </button>
              <button type="button" class="flex flex-1 items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs transition" :class="theme==='light'?'bg-white font-medium text-blue-600 shadow-sm dark:bg-slate-700 dark:text-blue-300':'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'" :aria-checked="theme==='light'" role="radio" @click="setTheme('light')">
                <Icon name="sun" :size="14"/><span>浅色</span>
              </button>
              <button type="button" class="flex flex-1 items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs transition" :class="theme==='dark'?'bg-white font-medium text-blue-600 shadow-sm dark:bg-slate-700 dark:text-blue-300':'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'" :aria-checked="theme==='dark'" role="radio" @click="setTheme('dark')">
                <Icon name="moon" :size="14"/><span>深色</span>
              </button>
            </div>
          </div>

        </div>
      </aside>
      <main class="surface relative flex min-w-0 flex-1 flex-col overflow-hidden rounded-2xl border divider lg:rounded-3xl">
        <template v-if="!activeId">
          <div class="flex h-16 items-center border-b divider px-6"><h1 class="text-sm font-medium">你的邮件空间</h1><span class="ml-auto caption">准备就绪</span></div>
          <div class="flex flex-1 flex-col items-center justify-center px-6 pb-12 text-center"><div class="relative mb-8"><div class="absolute -inset-5 rounded-full bg-blue-50 dark:bg-blue-900/10"></div><div class="relative flex h-24 w-24 items-center justify-center rounded-[28px] bg-[#e8f0fe] text-blue-500 dark:bg-blue-900/40"><Icon name="inbox" :size="44"/></div><span class="absolute -bottom-2 -right-2 rounded-full border-4 border-white bg-blue-600 p-2 text-white dark:border-slate-800"><Icon name="plus" :size="16"/></span></div><p class="mb-3 text-xs font-medium uppercase tracking-[.22em] text-blue-500">Your inbox starts here</p><h1 class="text-2xl font-semibold tracking-tight">欢迎来到你的新收件箱</h1><p class="mt-4 max-w-sm text-sm leading-7 text-slate-500">连接一个邮箱，就能在这里收发邮件、整理文件夹，找回专注的节奏。</p><button class="primary mt-7" @click="openConnect"><Icon name="plus" :size="18"/>连接邮箱</button><div class="mt-10 flex gap-7 text-xs text-slate-400"><span class="flex items-center gap-2"><Icon name="shield" :size="15"/>凭据加密</span><span class="flex items-center gap-2"><Icon name="grid" :size="15"/>多邮箱切换</span></div></div>
        </template>
        <template v-else>
          <div v-if="isAll?reconnectAccounts.length>0:activeAccount?.status==='reconnect'" class="flex items-center gap-3 bg-amber-50 px-5 py-3 text-xs text-amber-800 dark:bg-amber-950/40"><Icon name="alert" :size="16"/>{{isAll?reconnectAccounts.map(a=>a.email).join('、')+' 连接已失效，请重新连接。':'连接已失效，请重新连接或更新密码/授权码。'}}<button class="ml-auto underline" @click="openConnect">重新连接</button></div>
          <div class="flex min-h-16 shrink-0 items-center gap-1 overflow-x-auto border-b divider px-3 sm:px-5">
            <button v-if="activeThread" class="icon-btn" aria-label="返回列表" v-tip="'返回列表'" @click="activeThread='';threadAccount='';messages=[]"><Icon name="back"/></button><label v-else class="flex h-10 w-9 items-center justify-center"><input type="checkbox" :checked="allChecked" :disabled="!items.length" aria-label="选择本页全部邮件" class="h-4 w-4 accent-blue-600" @change="checkAll"/></label>
            <button class="icon-btn" :disabled="syncing||!activeId" aria-label="刷新邮箱" v-tip="syncing?'正在刷新…':'刷新邮箱'" @click="refresh()"><Icon name="refresh" :class="syncing?'animate-spin':''" :size="18"/></button>
            <template v-if="selected.length||activeThread"><span class="mx-1 h-5 border-l divider"></span><button class="icon-btn" :disabled="actionBusy" aria-label="归档" v-tip="'归档'" @click="modify([],['INBOX'])"><Icon name="archive" :size="18"/></button><button class="icon-btn" :disabled="actionBusy" aria-label="标记垃圾邮件" v-tip="'标记为垃圾邮件'" @click="modify(['SPAM'],['INBOX'])"><Icon name="alert" :size="18"/></button><button class="icon-btn" :disabled="actionBusy" :aria-label="folder==='TRASH'?'恢复邮件':'移入回收站'" v-tip="folder==='TRASH'?'恢复邮件':'移入回收站'" @click="modify([],[],folder==='TRASH'?'untrash':'trash')"><Icon :name="folder==='TRASH'?'inbox':'trash'" :size="18"/></button><button class="icon-btn" :disabled="actionBusy" aria-label="标记为未读" v-tip="'标记为未读'" @click="modify(['UNREAD'])"><Icon name="mail" :size="18"/></button><button v-if="!isAll" class="icon-btn" :disabled="actionBusy" aria-label="应用标签" v-tip="'应用标签'" @click="labelChoice=customLabels[0]?.id||'';modal='apply-label'"><Icon name="tag" :size="18"/></button></template>
            <span v-else class="ml-2 hidden text-sm font-medium sm:block">{{isAll?'全部账户 · '+folderTitle:folderTitle}}</span>
            <div v-if="!activeThread" class="ml-auto flex shrink-0 items-center gap-1"><span class="mr-2 hidden text-xs text-slate-400 sm:inline">{{items.length?`第 ${pageIndex+1} 页 · ${items.length} 个会话`:'暂无邮件'}}</span><button class="icon-btn" :disabled="pageIndex===0||loading" aria-label="上一页" v-tip="'上一页'" @click="paginate(-1)"><Icon name="chevron" :size="16" class="rotate-180"/></button><button class="icon-btn" :disabled="!nextCursor||loading" aria-label="下一页" v-tip="'下一页'" @click="paginate(1)"><Icon name="chevron" :size="16"/></button></div>
          </div>
          <div v-if="loading&&!items.length&&!activeThread||reading" class="flex flex-1 items-center justify-center gap-3 text-sm text-slate-400"><Icon name="refresh" class="animate-spin"/>正在加载邮件…</div>
          <div v-else-if="activeThread" class="flex-1 overflow-y-auto px-5 pb-10 sm:px-10"><h1 class="mb-6 mt-8 text-2xl font-medium leading-relaxed">{{messages[0]?.subject||'会话'}}</h1><article v-for="message in messages" :key="message.id" class="mb-5 border-b divider pb-6"><div class="flex items-start gap-3"><span class="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-sm font-medium text-blue-700 dark:bg-blue-900/40 dark:text-blue-200">{{initials(message.from)}}</span><div class="min-w-0 flex-1"><div class="flex flex-wrap items-center gap-2"><span class="text-sm font-semibold">{{senderName(message.from)}}</span><span class="ml-auto text-xs text-slate-400">{{shortDate(message.date)}}</span><button class="icon-btn !h-8 !w-8" aria-label="切换星标" v-tip="message.labels.includes('STARRED')?'取消星标':'加星标'" @click="star(message)"><Icon name="star" :size="17" :class="message.labels.includes('STARRED')?'fill-amber-400 text-amber-400':''"/></button></div><details class="text-xs text-slate-400"><summary class="cursor-pointer truncate">发送至 {{message.to}}</summary><div class="mt-2 space-y-1 break-all rounded-lg bg-slate-50 p-3 dark:bg-slate-800"><p>发件人：{{message.from}}</p><p>收件人：{{message.to}}</p><p v-if="message.cc">抄送：{{message.cc}}</p><p>{{message.date}}</p></div></details></div></div><div class="mt-5 sm:ml-13">                <div v-if="message.html" class="overflow-hidden rounded-xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-700/60">
                  <iframe
                    :key="message.id + '-' + (message.html?.length || 0)"
                    :srcdoc="message.html"
                    sandbox="allow-popups allow-popups-to-escape-sandbox allow-same-origin"
                    referrerpolicy="no-referrer"
                    class="w-full border-0 bg-white transition-[height] duration-150"
                    style="min-height: 380px; height: 380px;"
                    title="邮件正文"
                    @load="onIframeLoad"
                  ></iframe>
                </div>
                <pre v-else class="whitespace-pre-wrap break-words font-sans text-sm leading-7">{{message.text||'（无正文）'}}</pre><div v-if="message.attachments.length" class="mt-6 flex flex-wrap gap-2"><a v-for="attachment in message.attachments" :key="attachment.id" :href="'/api/v1'+path('/messages/'+encodeURIComponent(message.id)+'/attachments/'+encodeURIComponent(attachment.id),threadAid)" class="flex max-w-full items-center gap-3 rounded-xl border divider px-4 py-3 hover:bg-slate-50 dark:hover:bg-slate-800"><Icon name="attachment" :size="18" class="text-slate-400"/><span class="min-w-0"><span class="block truncate text-xs font-medium">{{attachment.name}}</span><span class="caption">{{sizeText(attachment.size)}}</span></span><Icon name="download" :size="15" class="text-slate-400"/></a></div><div class="mt-6 flex flex-wrap gap-2"><button class="secondary" @click="newCompose('reply',message)"><Icon name="reply" :size="16"/>回复</button><button class="secondary" @click="newCompose('replyAll',message)"><Icon name="replyAll" :size="16"/>回复全部</button><button class="secondary" @click="newCompose('forward',message)"><Icon name="forward" :size="16"/>转发</button></div></div></article></div>
          <div v-else-if="!items.length" class="flex flex-1 flex-col items-center justify-center px-6 pb-12 text-center"><span class="mb-5 flex h-20 w-20 items-center justify-center rounded-full bg-slate-50 text-slate-300 dark:bg-slate-800 dark:text-slate-600"><Icon :name="appliedQuery?'search':'inbox'" :size="34"/></span><h2 class="text-lg font-medium">{{appliedQuery?'没有找到匹配的邮件':'这里清清爽爽'}}</h2><p class="mt-3 text-sm text-slate-400">{{appliedQuery?'试试其他关键词，或使用 from:、subject: 搜索。':'当前文件夹暂无邮件。新邮件会在刷新后出现。'}}</p></div>
          <div v-else class="flex-1 overflow-y-auto"><div v-if="appliedQuery" class="border-b divider px-6 py-3 text-xs text-slate-400">搜索结果：{{appliedQuery}}</div><template v-for="item in items" :key="threadIdentity(item)"><div class="group flex cursor-pointer items-center gap-3 border-b divider px-4 py-3.5 transition hover:relative hover:z-10 hover:shadow-[0_1px_4px_#00000016] sm:gap-4 sm:px-5" :class="selected.includes(identity(item))?'bg-blue-50 dark:bg-blue-900/30':item.labels.includes('UNREAD')?'bg-white dark:bg-[#1e2939]':'bg-[#f8faff] dark:bg-[#17202c]'" @click="openMail(item)"><input v-model="selected" type="checkbox" :value="identity(item)" :aria-label="'选择 '+item.subject" class="h-4 w-4 shrink-0 accent-blue-600" @click.stop/><button class="shrink-0 text-slate-300" :aria-label="'切换星标 '+item.subject" v-tip="item.labels.includes('STARRED')?'取消星标':'加星标'" @click.stop="star(item)"><Icon name="star" :size="18" :class="item.labels.includes('STARRED')?'fill-amber-400 text-amber-400':''"/></button><button class="flex min-w-0 flex-1 flex-col gap-1 text-left md:flex-row md:items-center md:gap-6" @click.stop="openMail(item)"><span class="block w-full shrink-0" :class="isAll?'md:w-48':'md:w-40'"><span class="block truncate text-sm" :class="item.labels.includes('UNREAD')?'font-semibold':''">{{senderName(item.from)}} <span v-if="(item.count||0)>1" class="text-xs text-slate-400">{{item.count}}</span></span><span v-if="isAll" class="block truncate text-[11px] text-slate-400 dark:text-slate-500">{{(item.account||accountEmail(item.accountId))+' 的邮件'}}</span></span><span class="min-w-0 truncate text-sm"><span :class="item.labels.includes('UNREAD')?'font-semibold':''">{{item.subject}}</span><span v-if="item.snippet" class="ml-2 text-slate-400">— {{item.snippet}}</span></span></button><span class="shrink-0 text-xs" :class="item.labels.includes('UNREAD')?'font-semibold text-slate-600 dark:text-slate-200':'text-slate-400'">{{shortDate(item.date)}}</span></div></template><div class="px-5 py-8 text-center text-[11px] text-slate-400">{{isAll?'全部账户':activeAccount?.email}}</div></div>
        </template>
      </main>
    </div>
  </div>

  <div v-if="modal" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/35 p-4 backdrop-blur-sm" @click.self="modal=''">
    <section class="surface max-h-[90dvh] w-full overflow-y-auto rounded-3xl p-7 shadow-2xl" :class="modal==='connect'?'max-w-3xl':'max-w-lg'" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div class="mb-6 flex items-center justify-between"><h2 id="modal-title" class="text-xl font-semibold">{{modal==='connect'?'连接邮箱':modal==='settings'?'账户设置':modal==='label'?'管理标签':'应用标签'}}</h2><button class="icon-btn !h-8 !w-8" aria-label="关闭弹窗" v-tip="'关闭'" @click="modal=''"><Icon name="close" :size="19"/></button></div>
      <template v-if="modal==='connect'">
        <template v-if="connectMode==='choose'">
          <p class="mb-6 text-sm leading-6 text-slate-500">选择连接方式。已连接的邮箱会更新凭据或切换连接方式，邮件仍保留在原来的邮箱服务商。</p>
          <div class="grid gap-4 sm:grid-cols-2">
            <button class="flex w-full flex-col items-start gap-4 rounded-2xl border border-slate-200 p-6 text-left transition hover:border-blue-400 hover:shadow-lg hover:shadow-blue-500/10 dark:border-slate-700 dark:hover:border-blue-500" :disabled="!config.oauthEnabled||connectionBusy" @click="oauthConnect">
              <img src="/google-icon.svg" alt="" class="h-9 w-9" :class="connectionBusy?'animate-pulse':''"/>
              <span class="block text-sm font-semibold">使用 Google 连接</span>
            </button>
            <button class="flex w-full flex-col items-start gap-4 rounded-2xl border border-slate-200 p-6 text-left transition hover:border-blue-400 hover:shadow-lg hover:shadow-blue-500/10 dark:border-slate-700 dark:hover:border-blue-500" :disabled="connectionBusy" @click="connectMode='imap'">
              <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/40 dark:text-blue-300"><Icon name="mail" :size="20"/></span>
              <span class="block text-sm font-semibold">使用 IMAP＋SMTP 连接</span>
            </button>
          </div>
          <p v-if="!config.oauthEnabled" class="mt-4 text-xs leading-6 text-amber-600">尚未配置 Google OAuth。可在本地环境文件中配置，或使用 IMAP＋SMTP 连接。</p>
        </template>
        <template v-else>
          <button class="mb-5 inline-flex items-center gap-1.5 text-xs text-slate-500 transition hover:text-blue-600" @click="connectMode='choose'"><Icon name="back" :size="15"/>返回选择其他连接方式</button>
          <h3 class="text-base font-semibold">使用 IMAP＋SMTP 连接</h3>
          <p class="mt-2 text-xs leading-6 text-slate-400">支持任意开启 IMAP 与 SMTP 服务的邮箱。选择服务商会自动填充服务器，也可以手动改成自定义服务器。</p>
          <form class="mt-5 space-y-5" @submit.prevent="imapConnect">
            <div class="grid gap-4 sm:grid-cols-2">
              <label class="block text-xs">邮箱地址<input v-model="connection.email" type="email" required maxlength="254" class="field mt-2" placeholder="you@example.com" autocomplete="email" @input="detectProvider"/></label>
              <label class="block text-xs">密码 / 授权码<input v-model="connection.password" type="password" required minlength="1" maxlength="256" class="field mt-2" placeholder="邮箱密码或客户端授权码" autocomplete="off"/></label>
            </div>
            <label class="block text-xs">邮箱服务商<select v-model="connection.provider" class="field mt-2" @change="applyPreset(connection.provider)"><option value="">自动识别 / 请选择</option><option v-for="preset in config.mailProviders" :key="preset.id" :value="preset.id">{{preset.name}}</option><option value="custom">自定义 / 其他邮箱</option></select></label>
            <p v-if="presetHint" class="text-xs leading-6 text-slate-400">{{presetHint}}</p>
            <div class="grid gap-4 sm:grid-cols-2">
              <div class="rounded-xl border divider p-4">
                <p class="mb-3 text-xs font-medium text-slate-500">收信服务器（IMAP）</p>
                <label class="block text-xs">服务器地址<input v-model="connection.imapHost" required maxlength="253" class="field mt-2" placeholder="imap.example.com"/></label>
                <div class="mt-3 grid grid-cols-2 gap-3">
                  <label class="block text-xs">端口<input v-model.number="connection.imapPort" type="number" min="1" max="65535" required class="field mt-2"/></label>
                  <label class="block text-xs">加密方式<select v-model="connection.imapSecurity" class="field mt-2"><option value="ssl">SSL/TLS</option><option value="starttls">STARTTLS</option></select></label>
                </div>
              </div>
              <div class="rounded-xl border divider p-4">
                <p class="mb-3 text-xs font-medium text-slate-500">发信服务器（SMTP）</p>
                <label class="block text-xs">服务器地址<input v-model="connection.smtpHost" required maxlength="253" class="field mt-2" placeholder="smtp.example.com"/></label>
                <div class="mt-3 grid grid-cols-2 gap-3">
                  <label class="block text-xs">端口<input v-model.number="connection.smtpPort" type="number" min="1" max="65535" required class="field mt-2"/></label>
                  <label class="block text-xs">加密方式<select v-model="connection.smtpSecurity" class="field mt-2"><option value="ssl">SSL/TLS</option><option value="starttls">STARTTLS</option></select></label>
                </div>
              </div>
            </div>
            <p class="text-xs leading-6 text-slate-400">连接时会同时验证收信与发信两个通道，任一失败都不会保存。多数邮箱需要先开启 IMAP/SMTP 服务并使用授权码或专用密码。</p>
            <button class="primary w-full" :disabled="connectionBusy"><Icon v-if="connectionBusy" name="refresh" class="animate-spin" :size="16"/>{{connectionBusy?'正在验证两个通道…':'验证并连接'}}</button>
          </form>
        </template>
      </template>
      <template v-else-if="modal==='settings'"><div class="mb-6 flex items-center gap-3 rounded-xl bg-slate-50 p-4 dark:bg-slate-800"><span class="flex h-10 w-10 items-center justify-center rounded-full bg-blue-100 text-blue-700">{{initials(user.email)}}</span><div><p class="text-sm font-semibold break-all">{{user.email}}</p><p class="caption mt-1">Hmail 平台账户</p></div><button class="secondary ml-auto !px-3 !text-xs" @click="logout"><Icon name="logout" :size="14"/>退出</button></div><h3 class="mb-3 text-sm font-medium">已连接邮箱</h3><div v-for="account in accounts" :key="account.id" class="mb-3 flex items-center gap-3 rounded-xl border divider p-3"><div class="min-w-0 flex-1"><p class="truncate text-sm">{{account.email}}</p><p class="caption mt-1 flex flex-wrap items-center gap-2"><span>{{account.provider==='oauth'?'Google OAuth':'IMAP＋SMTP'}}</span><span v-if="account.isDefault" class="rounded-full bg-blue-50 px-2 py-0.5 text-[11px] text-blue-600 dark:bg-blue-900/40 dark:text-blue-300">默认邮箱</span></p></div><button class="shrink-0 text-xs" :class="account.isDefault?'text-slate-400 hover:text-slate-600 dark:hover:text-slate-200':'text-blue-600 dark:text-blue-300'" @click="setDefaultAccount(account)">{{account.isDefault?'取消默认':'设为默认'}}</button><button class="shrink-0 text-xs text-slate-400 hover:text-red-500" @click="disconnect(account)">断开</button></div><button class="mb-6 text-sm text-blue-600" @click="openConnect">＋ 连接另一个邮箱</button><details class="border-t divider pt-4"><summary class="cursor-pointer text-sm">修改密码</summary><form class="mt-4 space-y-3" @submit.prevent="changePassword"><input v-model="changingPassword.currentPassword" type="password" required class="field" placeholder="当前密码" autocomplete="current-password"/><input v-model="changingPassword.password" type="password" required minlength="10" class="field" placeholder="新密码（至少 10 个字符）" autocomplete="new-password"/><p class="text-xs leading-6 text-slate-400">修改密码会撤销所有已登录会话，需要重新登录。</p><button class="primary" type="submit">保存并重新登录</button></form></details></template>
      <form v-else-if="modal==='label'" @submit.prevent="saveLabel"><label class="block text-sm">标签名称<input v-model="labelName" class="field mt-2" required maxlength="200" autofocus/></label><div class="mt-6 flex justify-between"><button v-if="labelEdit" type="button" class="text-sm text-red-500" @click="deleteLabel(labelEdit);modal=''">删除标签</button><button class="primary ml-auto">保存</button></div></form>
      <form v-else @submit.prevent="modify([labelChoice]);modal='' "><select v-model="labelChoice" class="field" required><option value="" disabled>选择标签</option><option v-for="label in customLabels" :key="label.id" :value="label.id">{{label.name}}</option></select><p v-if="!customLabels.length" class="mt-3 text-xs text-slate-400">请先在侧边栏创建标签。</p><div class="mt-5 flex justify-end gap-2"><button type="button" class="secondary" :disabled="!labelChoice" @click="modify([],[labelChoice]);modal=''">移除标签</button><button class="primary" :disabled="!labelChoice">应用</button></div></form>
    </section>
  </div>

  <ComposeDialog :open="composeVisible" @stash="stashCompose">
    <header class="flex shrink-0 flex-wrap items-center justify-between gap-y-2 bg-[#eaf0fa] px-5 py-3 dark:bg-slate-800"><h2 class="shrink-0 text-sm font-medium">新邮件</h2><span class="ml-auto mr-3 text-[11px] text-slate-400" aria-live="polite">{{savedState}}</span><button class="mr-3 rounded-lg px-2 py-1 text-xs text-blue-600 dark:text-blue-300" @click="stashCompose">收起</button><button class="text-slate-500" aria-label="保存并关闭" v-tip="'保存并关闭'" :disabled="composingBusy||uploading" @click="closeCompose"><Icon name="close" :size="18"/></button></header>
    <form class="flex min-h-0 flex-1 flex-col" @submit.prevent="sendMail"><div class="min-h-0 overflow-y-auto px-5"><div class="border-b divider py-3 text-xs text-slate-400">发件人 <span class="ml-3 text-slate-600 dark:text-slate-300">{{accounts.find(a=>a.id===composeAccount)?.email}}</span></div><label v-for="field in (['to','cc','bcc'] as const)" :key="field" class="flex items-center border-b divider text-sm text-slate-400"><span class="w-14 shrink-0">{{field==='to'?'收件人':field==='cc'?'抄送':'密送'}}</span><input v-model="draft[field]" :disabled="sendUncertain" class="w-full bg-transparent py-3 text-slate-700 focus-visible:ring-0 dark:text-slate-200" :aria-label="field" @input="dirty"/></label><input v-model="draft.subject" :disabled="sendUncertain" class="w-full border-b divider bg-transparent py-3 text-sm focus-visible:ring-0" placeholder="主题" aria-label="邮件主题" @input="dirty"/><textarea v-model="draft.text" :disabled="sendUncertain" class="min-h-[240px] w-full resize-y bg-transparent py-4 text-sm leading-7 focus-visible:ring-0" placeholder="写下你的邮件…" aria-label="邮件正文" @input="dirty"></textarea><div class="mb-3 flex flex-wrap gap-2"><div v-for="(attachment,index) in draft.attachments" :key="attachment.id" class="flex max-w-full items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 text-xs dark:bg-slate-800"><Icon name="attachment" :size="14"/><span class="max-w-[230px] truncate">{{attachment.name}}</span><span class="text-slate-400">{{sizeText(attachment.size)}}</span><button type="button" :disabled="sendUncertain" aria-label="移除附件" @click="draft.attachments.splice(index,1);dirty()"><Icon name="close" :size="13"/></button></div></div></div><p v-if="sendUncertain" class="mx-5 mb-3 rounded-lg bg-amber-50 p-3 text-xs leading-6 text-amber-700">发送结果待确认，正在自动核对…请勿重复发送；也可稍后在“已发送”中确认。</p><footer class="flex shrink-0 flex-wrap items-center gap-3 border-t divider p-4"><button class="primary" :disabled="composingBusy||uploading||sendUncertain"><Icon name="send" :size="16"/>{{composingBusy?'处理中…':'发送'}}</button><label class="icon-btn cursor-pointer" aria-label="添加附件" v-tip="'添加附件'"><Icon :name="uploading?'refresh':'attachment'" :class="uploading?'animate-spin':''"/><input type="file" multiple class="sr-only" :disabled="composingBusy||uploading||sendUncertain" @change="uploadFiles"/></label><button type="button" class="text-xs text-slate-400" :disabled="composingBusy||uploading||sendUncertain" @click="saveDraft">保存草稿</button><button type="button" class="icon-btn ml-auto" :disabled="composingBusy||uploading" aria-label="丢弃草稿" v-tip="'丢弃草稿'" @click="discardCompose"><Icon name="trash" :size="18"/></button></footer></form>
  </ComposeDialog>
  <button v-if="user && composer && composeStashed" class="compose-stash surface" aria-label="恢复暂存邮件" @click="restoreCompose">
    <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-100 text-blue-600 dark:bg-blue-900/40 dark:text-blue-300"><Icon name="edit" :size="19"/></span>
    <span class="min-w-0 flex-1 text-left"><span class="block truncate text-sm font-medium">{{draft.subject || '未命名邮件'}}</span><span class="mt-1 block truncate text-xs" :class="savedState.includes('失败')?'text-red-500':'text-slate-500 dark:text-slate-400'" aria-live="polite">{{savedState || '已暂存'}} · {{savedState.includes('失败')?'点击恢复并重试':'点击继续编辑'}}</span></span>
    <Icon name="chevron" class="-rotate-90 text-slate-400" :size="16"/>
  </button>
  <div v-if="error" role="alert" class="fixed left-1/2 top-4 z-[70] flex w-[calc(100%-2rem)] max-w-xl -translate-x-1/2 items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-5 py-4 text-sm text-red-800 shadow-lg"><Icon name="alert" class="mt-0.5 shrink-0" :size="18"/><span class="flex-1 leading-6">{{error}}</span><button aria-label="关闭错误提示" @click="error=''"><Icon name="close" :size="17"/></button></div>
  <div v-if="notice" role="status" class="fixed bottom-6 left-1/2 z-[70] flex max-w-[90vw] -translate-x-1/2 items-center gap-3 rounded-xl bg-slate-800 px-5 py-3.5 text-sm text-white shadow-xl"><Icon name="check" :size="17" class="shrink-0 text-emerald-400"/>{{notice}}<button aria-label="关闭提示" @click="notice=''"><Icon name="close" :size="15"/></button></div>
</template>
