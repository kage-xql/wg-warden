import { useEffect,useMemo,useState,type FormEvent } from 'react'
import { useMutation,useQuery,useQueryClient } from '@tanstack/react-query'
import { Area,AreaChart,CartesianGrid,Line,LineChart,ResponsiveContainer,Tooltip,XAxis,YAxis } from 'recharts'
import wireguardLogo from './assets/wireguard-dragon.jpeg'

type Server={id:string;name:string;host:string;sshPort:number;sshUser:string;operatingSystem:string|null;status:'PENDING'|'ONLINE'|'ERROR';connectionError:string|null;hostKeyFingerprint:string|null}
type Peer={id:string;name:string;publicKey:string;allowedIp:string;enabled:boolean;createdAt:string;trafficLimitBytes:number|null;expiresAt:string|null;configAvailable:boolean}
type ServerMetric={timestamp:string;cpu:number;ram:number;disk:number;netRx:number;netTx:number}
type PeerMetric={timestamp:string;rxBytes:number;txBytes:number;lastHandshake:string|null;online:boolean}
type PeerCreated={peer:Peer;config:string;qrCodePngBase64:string}
type DiscoveredPeer={name:string;publicKey:string;allowedIp:string}
type DiscoveredInterface={name:string;listenPort:number;subnet:string;publicKey:string;peers:DiscoveredPeer[]}
type MonitorUpdate={serverId:string;status:string;connectionError:string|null;server:ServerMetric|null;peers:{peerId:string;enabled?:boolean;metrics:PeerMetric|null}[]}
type ConfirmAction={title:string;message:string;onConfirm:()=>void}
const TOKEN='wg-warden-token'

async function api<T>(path:string,token:string,init:RequestInit={}):Promise<T>{
  const response=await fetch(path,{...init,headers:{...(init.body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:`Bearer ${token}`}:{}) ,...init.headers}})
  if(response.status===401){window.dispatchEvent(new Event('wg-warden-auth-expired'));throw new Error('Сессия истекла. Войдите снова.')}
  if(!response.ok){const body=await response.json().catch(()=>({error:response.statusText}));throw new Error(body.error??`HTTP ${response.status}`)}
  if(response.status===204)return undefined as T
  return response.json() as Promise<T>
}
function bytes(n:number){if(n<1024)return `${n} B`;const units=['KB','MB','GB','TB'];let v=n/1024,i=0;while(v>=1024&&i<units.length-1){v/=1024;i++}return `${v.toFixed(1)} ${units[i]}`}
function time(value:string){return new Date(value).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'})}
function dateTime(value:string|null){return value?new Date(value).toLocaleString(): '—'}
function peerLimits(peer:Peer){
  const parts=[];
  if(peer.trafficLimitBytes)parts.push(`≤ ${bytes(peer.trafficLimitBytes)}`);
  if(peer.expiresAt){
    const expired=new Date(peer.expiresAt).getTime()<=Date.now();
    parts.push(`until ${new Date(peer.expiresAt).toLocaleString()}${expired?' (expired)':''}`);
  }
  return parts.join(' · ')
}

export default function App(){
  const [token,setToken]=useState(()=>localStorage.getItem(TOKEN)??'')
  const [selectedId,setSelectedId]=useState('')
  const [showServerForm,setShowServerForm]=useState(false)
  const [serverToEdit,setServerToEdit]=useState<Server|null>(null)
  const [showPeerForm,setShowPeerForm]=useState(false)
  const [showImport,setShowImport]=useState(false)
  const [oneTime,setOneTime]=useState<PeerCreated|null>(null)
  const [editingPeer,setEditingPeer]=useState<Peer|null>(null)
  const [confirmAction,setConfirmAction]=useState<ConfirmAction|null>(null)
  const [focusedPeerId,setFocusedPeerId]=useState('')
  const [streamState,setStreamState]=useState<'connecting'|'live'|'paused'>('connecting')
  const [authError,setAuthError]=useState('')
  const [provisionError,setProvisionError]=useState('')
  const [deleteError,setDeleteError]=useState('')
  const qc=useQueryClient()
  useEffect(()=>{const logout=()=>{localStorage.removeItem(TOKEN);setToken('');qc.clear()};window.addEventListener('wg-warden-auth-expired',logout);return()=>window.removeEventListener('wg-warden-auth-expired',logout)},[qc])
  useEffect(()=>{const show=(event:Event)=>setOneTime((event as CustomEvent<PeerCreated>).detail);window.addEventListener('wg-warden-config',show);return()=>window.removeEventListener('wg-warden-config',show)},[])
  const serverQuery=useQuery({queryKey:['servers',token],queryFn:()=>api<Server[]>('/api/servers',token),enabled:!!token})
  const servers=serverQuery.data??[]
  const deleteMutation=useMutation({mutationFn:(id:string)=>api<void>(`/api/servers/${id}`,token,{method:'DELETE'}),onSuccess:(_,id)=>{setDeleteError('');setSelectedId(current=>current===id?'':current);qc.removeQueries({queryKey:['server-metrics',token,id]});void serverQuery.refetch()},onError:e=>setDeleteError(e.message)})
  const provisionMutation=useMutation({mutationFn:()=>api<unknown>(`/api/servers/${activeId}/provisioning/wireguard`,token,{method:'POST'}),onSuccess:()=>{setProvisionError('');void serverQuery.refetch()},onError:e=>setProvisionError(e.message)})
  const activeId=servers.some(s=>s.id===selectedId)?selectedId:(servers[0]?.id??'')
  const active=servers.find(s=>s.id===activeId)
  const peersQuery=useQuery({queryKey:['peers',token,activeId],queryFn:()=>api<Peer[]>(`/api/servers/${activeId}/peers`,token),enabled:!!token&&!!activeId})
  const metricsQuery=useQuery({queryKey:['server-metrics',token,activeId],queryFn:()=>api<ServerMetric[]>(`/api/servers/${activeId}/metrics`,token),enabled:!!token&&!!activeId,refetchInterval:30000})
  const peers=peersQuery.data??[]
  const focusedPeer=peers.find(p=>p.id===focusedPeerId)
  const focusedMetrics=useQuery({queryKey:['peer-metrics',token,focusedPeerId],queryFn:()=>api<PeerMetric[]>(`/api/peers/${focusedPeerId}/metrics`,token),enabled:!!token&&!!focusedPeerId})

  useEffect(()=>{
    if(!token)return
    const abort=new AbortController()
    const pause=(ms:number)=>new Promise<void>(resolve=>{const id=setTimeout(resolve,ms);abort.signal.addEventListener('abort',()=>{clearTimeout(id);resolve()},{once:true})})
    const receive=async()=>{
      while(!abort.signal.aborted){
        try{
          setStreamState('connecting')
          const response=await fetch('/api/events',{headers:{Authorization:`Bearer ${token}`},signal:abort.signal})
          if(response.status===401){window.dispatchEvent(new Event('wg-warden-auth-expired'));break}
          if(!response.ok||!response.body)throw new Error(`SSE HTTP ${response.status}`)
          setStreamState('live')
          const reader=response.body.getReader(),decoder=new TextDecoder();let buffer=''
          while(!abort.signal.aborted){
            const part=await reader.read();if(part.done)break
            buffer+=decoder.decode(part.value,{stream:true});let boundary:number
            while((boundary=buffer.indexOf('\n\n'))>=0){
              const raw=buffer.slice(0,boundary);buffer=buffer.slice(boundary+2)
              const line=raw.split('\n').find(value=>value.startsWith('data:'))
              if(!line)continue
              const update=JSON.parse(line.slice(5).trim()) as MonitorUpdate
              qc.setQueryData<Server[]>(['servers',token],old=>old?.map(s=>s.id===update.serverId?{...s,status:update.status as Server['status'],connectionError:update.connectionError}:s))
              if(update.server)qc.setQueryData<ServerMetric[]>(['server-metrics',token,update.serverId],old=>[...(old??[]),update.server!].slice(-1440))
              for(const item of update.peers){
                if(item.metrics)qc.setQueryData<PeerMetric[]>(['peer-metrics',token,item.peerId],old=>[...(old??[]),item.metrics!].slice(-1440))
                if(typeof item.enabled==='boolean'){
                  qc.setQueryData<Peer[]>(['peers',token,update.serverId],old=>old?.map(p=>p.id===item.peerId?{...p,enabled:item.enabled!}:p))
                }
              }
            }
          }
          if(!abort.signal.aborted)throw new Error('SSE stream ended')
        }catch(error){if(abort.signal.aborted)break;setStreamState('paused');await pause(2500)}
      }
    }
    void receive();return()=>abort.abort()
  },[token,qc])

  const chartData=useMemo(()=> (metricsQuery.data??[]).map(m=>({...m,time:time(m.timestamp)})),[metricsQuery.data])
  const latest=chartData.at(-1)
  if(!token)return <AuthScreen onSuccess={value=>{localStorage.setItem(TOKEN,value);setToken(value)}} error={authError} setError={setAuthError}/>

  return <div className="shell">
    <aside className="sidebar">
      <div className="brand"><span className="brand-glyph"><img className="wireguard-logo" src={wireguardLogo} alt="WireGuard"/></span><div><strong>wg-warden</strong><small>SELF-HOSTED NETWORKS</small></div></div>
      <div className="nav-heading">SERVERS <button className="square" aria-label="Добавить сервер" onClick={()=>setShowServerForm(true)}>+</button></div>
      {serverQuery.isLoading&&<div className="side-message">Загружаю список серверов…</div>}
      {serverQuery.isError&&<div className="side-error">{serverQuery.error.message}<button onClick={()=>void serverQuery.refetch()}>Повторить</button></div>}
      {!serverQuery.isLoading&&serverQuery.isSuccess&&servers.length===0&&<div className="side-message">Серверов пока нет.</div>}
      <nav className="server-list">{servers.map(server=><button key={server.id} className={`server-nav ${server.id===activeId?'active':''}`} onClick={()=>{setSelectedId(server.id);setFocusedPeerId('');setShowPeerForm(false)}}>
        <StatusMark status={server.status}/><span><strong>{server.name}</strong><small>{server.host}</small></span>
      </button>)}</nav>
      <div className="sidebar-bottom"><span className={`stream-state ${streamState}`}><i/>{streamState==='live'?'LIVE · SSE':streamState==='paused'?'SSE PAUSED':'CONNECTING'}</span><button className="logout" onClick={()=>{localStorage.removeItem(TOKEN);setToken('');qc.clear()}}>Sign out ↗</button></div>
    </aside>

    <main className="main">
      <header className="topbar"><div className="crumb">WG / <b>{active?.name??'OVERVIEW'}</b></div><div className="topbar-right"><span className={`stream-state ${streamState}`}><i/>{streamState==='live'?'LIVE':streamState==='paused'?'UPDATES PAUSED':'CONNECTING'}</span><span className="user-chip">{servers.length} SERVER{servers.length===1?'':'S'}</span></div></header>
      {!active&&<section className="welcome panel"><div className="eyebrow">WIREGUARD CONTROL PLANE</div><h1>{serverQuery.isLoading?'Loading your workspace':serverQuery.isError?'Could not load servers':'No servers connected'}</h1><p>{serverQuery.isLoading?'Fetching the latest server list…':serverQuery.isError?serverQuery.error.message:'Add a VPS to inspect its status, peers and network metrics.'}</p>{serverQuery.isError?<button className="button" onClick={()=>void serverQuery.refetch()}>Retry request</button>:!serverQuery.isLoading&&<button className="button" onClick={()=>setShowServerForm(true)}>＋ Add server</button>}</section>}
      {active&&<>
        <section className="server-heading"><div><div className="eyebrow">SERVER OVERVIEW <span className="slash">/</span> {active.operatingSystem??'OS UNKNOWN'}</div><h1>{active.name}</h1><p className="hostline">{active.host}:{active.sshPort} <span>·</span> {active.sshUser}</p></div><div className="heading-actions"><button className="button" onClick={()=>setServerToEdit(active)}>Edit SSH access</button><button className="button danger" disabled={deleteMutation.isPending} onClick={()=>setConfirmAction({title:'Delete server?',message:`${active.name}, its peers and metrics will be removed. wg-warden will also stop WireGuard and delete its managed config on the VPS. If remote cleanup fails, the server is kept so access is not silently left active.`,onConfirm:()=>deleteMutation.mutate(active.id)})}>{deleteMutation.isPending?'Deleting…':'Delete server'}</button><button className="button primary" disabled={active.status!=='ONLINE'||provisionMutation.isPending} onClick={()=>provisionMutation.mutate()}>{provisionMutation.isPending?'Installing…':'Provision WireGuard'}</button><div className={`status-badge ${active.status.toLowerCase()}`}><StatusMark status={active.status}/>{active.status}</div></div></section>
        {provisionError&&<div className="alert error"><span>!</span><div><strong>WireGuard provisioning failed</strong><p>{provisionError}</p></div></div>}
        {deleteError&&<div className="alert error"><span>!</span><div><strong>Server was not deleted</strong><p>{deleteError}</p></div><button onClick={()=>setDeleteError('')}>Dismiss</button></div>}
        {active.status==='ERROR'&&<div className="alert error"><span>!</span><div><strong>Server connection failed</strong><p>{active.connectionError||'SSH connection unavailable. Check the host and credentials.'}</p></div><button onClick={()=>void serverQuery.refetch()}>Refresh status</button></div>}
        {peersQuery.isError&&<div className="alert error"><span>!</span><div><strong>Could not load peers</strong><p>{peersQuery.error.message}</p></div><button onClick={()=>void peersQuery.refetch()}>Retry</button></div>}
        {metricsQuery.isError&&<div className="alert error"><span>!</span><div><strong>Could not load server metrics</strong><p>{metricsQuery.error.message}</p></div><button onClick={()=>void metricsQuery.refetch()}>Retry</button></div>}
        <div className="stats-grid">
          <Stat title="CPU LOAD" value={latest?`${latest.cpu.toFixed(1)}%`:'—'} detail="1m system load" tone="green"/>
          <Stat title="MEMORY" value={latest?`${latest.ram.toFixed(1)}%`:'—'} detail="used RAM" tone="blue"/>
          <Stat title="ROOT DISK" value={latest?`${latest.disk.toFixed(1)}%`:'—'} detail="used filesystem" tone="amber"/>
          <Stat title="PEERS ENABLED" value={`${peers.filter(p=>p.enabled).length}`} detail={`${peers.length} configured`} tone="purple"/>
        </div>
        <section className="charts-grid">
          <article className="panel chart-panel"><PanelTitle title="System resources" subtitle="Rolling history · last 24 hours"/><ChartState loading={metricsQuery.isLoading} empty={!chartData.length} error={metricsQuery.isError}/>
            {!metricsQuery.isLoading&&!metricsQuery.isError&&chartData.length>0&&<div className="chart"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chartData}><defs><linearGradient id="cpu" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#a3e635" stopOpacity={.2}/><stop offset="100%" stopColor="#a3e635" stopOpacity={0}/></linearGradient></defs><CartesianGrid stroke="#292c29" vertical={false}/><XAxis dataKey="time" tickLine={false} axisLine={false} tick={{fill:'#777d73',fontSize:10}} minTickGap={35}/><YAxis domain={[0,100]} unit="%" tickLine={false} axisLine={false} tick={{fill:'#777d73',fontSize:10}}/><Tooltip contentStyle={{background:'#181a18',border:'1px solid #3a4037',borderRadius:4,fontFamily:'monospace'}}/><Area name="CPU load" dataKey="cpu" type="monotone" stroke="#a3e635" fill="url(#cpu)" strokeWidth={1.8}/><Line name="RAM" dataKey="ram" type="monotone" stroke="#60a5fa" strokeWidth={1.7} dot={false}/><Line name="Disk" dataKey="disk" type="monotone" stroke="#fbbf24" strokeWidth={1.5} dot={false}/></AreaChart></ResponsiveContainer></div>}
            <div className="chart-legend"><span className="green">● CPU</span><span className="blue">● RAM</span><span className="amber">● DISK</span></div>
          </article>
          <article className="panel chart-panel"><PanelTitle title="Network traffic" subtitle="Cumulative host interface bytes"/><ChartState loading={metricsQuery.isLoading} empty={!chartData.length} error={metricsQuery.isError}/>
            {!metricsQuery.isLoading&&!metricsQuery.isError&&chartData.length>0&&<div className="chart"><ResponsiveContainer width="100%" height="100%"><LineChart data={chartData}><CartesianGrid stroke="#292c29" vertical={false}/><XAxis dataKey="time" tickLine={false} axisLine={false} tick={{fill:'#777d73',fontSize:10}} minTickGap={35}/><YAxis tickLine={false} axisLine={false} tick={{fill:'#777d73',fontSize:10}} tickFormatter={v=>bytes(Number(v))}/><Tooltip contentStyle={{background:'#181a18',border:'1px solid #3a4037',borderRadius:4,fontFamily:'monospace'}} formatter={v=>bytes(Number(v))}/><Line name="RX" dataKey="netRx" type="monotone" stroke="#a3e635" strokeWidth={1.8} dot={false}/><Line name="TX" dataKey="netTx" type="monotone" stroke="#60a5fa" strokeWidth={1.8} dot={false}/></LineChart></ResponsiveContainer></div>}
            <div className="chart-legend"><span className="green">↓ {bytes(latest?.netRx??0)}</span><span className="blue">↑ {bytes(latest?.netTx??0)}</span></div>
          </article>
        </section>
        {focusedPeer&&<PeerChart peer={focusedPeer} data={focusedMetrics.data??[]} loading={focusedMetrics.isLoading} error={focusedMetrics.isError}/>}
        <section className="panel peer-panel"><div className="panel-header"><PanelTitle title="WireGuard peers" subtitle="Peer status from the latest handshake"/><div className="panel-actions"><button className="button quiet" onClick={()=>setShowImport(true)} disabled={active.status!=='ONLINE'}>＋ Import existing</button><button className="button" onClick={()=>setShowPeerForm(show=>!show)} disabled={active.status!=='ONLINE'}>＋ Add peer</button></div></div>
          {active.status!=='ONLINE'&&<div className="inline-note">Peer actions are unavailable while the server is offline.</div>}
          {peersQuery.isLoading?<div className="state-box">Loading peer list…</div>:peersQuery.isError?null:peers.length===0?<div className="state-box">No peers yet. Create one to get a one-time config and QR code.</div>:<div className="table-scroll"><table><thead><tr><th>NAME / PUBLIC KEY</th><th>CLIENT ADDRESS</th><th>STATE</th><th>LAST HANDSHAKE</th><th>RX / TX</th><th/></tr></thead><tbody>{peers.map(peer=><PeerRow key={peer.id} peer={peer} token={token} selected={focusedPeerId===peer.id} onSelect={()=>setFocusedPeerId(peer.id)} disabled={active.status!=='ONLINE'} onChanged={()=>void peersQuery.refetch()} onEdit={()=>setEditingPeer(peer)} onRequestDelete={action=>setConfirmAction({title:'Delete peer?',message:`${peer.name} and its server configuration will be permanently removed.`,onConfirm:action})}/>)}</tbody></table></div>}
          {showPeerForm&&active.status==='ONLINE'&&<PeerForm token={token} serverId={active.id} onCreated={created=>{setOneTime(created);setShowPeerForm(false);void peersQuery.refetch()}}/>}
        </section>
      </>}
    </main>
    {showServerForm&&<ServerDialog token={token} onClose={()=>setShowServerForm(false)} onSaved={server=>{setSelectedId(server.id);void serverQuery.refetch()}}/>}
    {serverToEdit&&<ServerDialog token={token} server={serverToEdit} onClose={()=>setServerToEdit(null)} onSaved={server=>{setSelectedId(server.id);void serverQuery.refetch()}}/>}
    {oneTime&&<ConfigDialog result={oneTime} onClose={()=>setOneTime(null)}/>}
    {showImport&&active&&<ImportDialog token={token} serverId={active.id} onClose={()=>setShowImport(false)} onImported={()=>{setShowImport(false);void peersQuery.refetch()}}/>}
    {editingPeer&&<PeerEditDialog token={token} peer={editingPeer} onClose={()=>setEditingPeer(null)} onSaved={()=>{setEditingPeer(null);void peersQuery.refetch()}}/>}
    {confirmAction&&<ConfirmDialog action={confirmAction} onClose={()=>setConfirmAction(null)} onConfirm={()=>{confirmAction.onConfirm();setConfirmAction(null)}}/>}
  </div>
}

function StatusMark({status}:{status:Server['status']}){return <i className={`status-mark ${status.toLowerCase()}`} aria-label={status}/>}
function Stat({title,value,detail,tone}:{title:string;value:string;detail:string;tone:string}){return <article className="stat panel"><div className="eyebrow"><i className={tone}/> {title}</div><strong>{value}</strong><small>{detail}</small></article>}
function PanelTitle({title,subtitle}:{title:string;subtitle:string}){return <div className="panel-title"><h2>{title}</h2><p>{subtitle}</p></div>}
function ChartState({loading,empty,error}:{loading:boolean;empty:boolean;error:boolean}){if(loading)return <div className="chart-state">Loading metrics…</div>;if(error)return <div className="chart-state error-text">Metrics could not be loaded.</div>;if(empty)return <div className="chart-state">No history yet. Metrics appear after the first successful poll.</div>;return null}

function AuthScreen({onSuccess,error,setError}:{onSuccess:(token:string)=>void;error:string;setError:(value:string)=>void}){
  const [mode,setMode]=useState<'login'|'register'>('login');const [email,setEmail]=useState('');const [password,setPassword]=useState('');const [setupToken,setSetupToken]=useState('');const [busy,setBusy]=useState(false)
  async function submit(event:FormEvent){event.preventDefault();setBusy(true);setError('');try{const result=await api<{token:string}>(`/api/auth/${mode}`,'',{method:'POST',body:JSON.stringify({email,password,setupToken:setupToken||undefined})});onSuccess(result.token)}catch(e){setError(e instanceof Error?e.message:'Authentication failed')}finally{setBusy(false)}}
  return <main className="auth-wrap"><section className="auth-card panel"><div className="brand auth-brand"><span className="brand-glyph"><img className="wireguard-logo" src={wireguardLogo} alt="WireGuard"/></span><div><strong>wg-warden</strong><small>SELF-HOSTED NETWORKS</small></div></div><div className="eyebrow">AUTH / {mode.toUpperCase()}</div><h1>{mode==='login'?'Sign in':'Create account'}</h1><p>Secure control plane for your WireGuard servers.</p><form onSubmit={submit}><label>Email<input type="email" required autoComplete="email" value={email} onChange={e=>setEmail(e.target.value)}/></label><label>Password<input type="password" required minLength={8} autoComplete={mode==='login'?'current-password':'new-password'} value={password} onChange={e=>setPassword(e.target.value)}/></label>{mode==='register'&&<label>Setup token<input type="password" autoComplete="off" value={setupToken} onChange={e=>setSetupToken(e.target.value)} placeholder="Required only for the first account"/><small className="form-hint">Printed by install.sh or stored as APP_BOOTSTRAP_TOKEN.</small></label>}{error&&<div className="form-error">{error}</div>}<button className="button primary wide" disabled={busy}>{busy?'Checking credentials…':mode==='login'?'Continue':'Register'}</button></form><button className="link-button" onClick={()=>{setMode(mode==='login'?'register':'login');setError('')}}>{mode==='login'?'Create an account':'Back to sign in'}</button></section></main>
}

function ServerDialog({token,server,onClose,onSaved}:{token:string;server?:Server;onClose:()=>void;onSaved:(server:Server)=>void}){
  const [name,setName]=useState(server?.name??'');const [host,setHost]=useState(server?.host??'');const [sshPort,setSshPort]=useState(String(server?.sshPort??22));const [sshUser,setSshUser]=useState(server?.sshUser??'');const [password,setPassword]=useState('');const [privateKey,setPrivateKey]=useState('');const [passphrase,setPassphrase]=useState('');const [trustNewHostKey,setTrustNewHostKey]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [result,setResult]=useState<Server|null>(null)
  const editing=Boolean(server)
  const credentialsProvided=Boolean(password.trim())||Boolean(privateKey.trim())
  const secretValid=editing?!credentialsProvided||Boolean(password.trim())!==Boolean(privateKey.trim()):Boolean(password.trim())!==Boolean(privateKey.trim())
  async function submit(event:FormEvent){event.preventDefault();if(!secretValid){setError('Enter exactly one credential: a password or a private key.');return}setBusy(true);setError('');try{const credentials=credentialsProvided?{password:password||undefined,privateKey:privateKey||undefined,passphrase:passphrase||undefined}:undefined;const payload=JSON.stringify({name,host,sshPort:Number(sshPort),sshUser,credentials,trustNewHostKey:trustNewHostKey||undefined});const saved=await api<Server>(server?`/api/servers/${server.id}`:'/api/servers',token,{method:server?'PUT':'POST',body:payload});setPassword('');setPrivateKey('');setPassphrase('');setResult(saved);onSaved(saved)}catch(e){setError(e instanceof Error?e.message:'SSH check failed')}finally{setBusy(false)}}
  return <div className="overlay" onMouseDown={e=>e.target===e.currentTarget&&onClose()}><section className="dialog panel"><button className="close" onClick={onClose}>×</button>{!result?<><div className="eyebrow">CONNECTION / SSH</div><h2>{editing?'Repair server connection':'Add server'}</h2><p className="dialog-copy">{editing?'Saved SSH credentials stay encrypted and are reused if you leave both credential fields empty. Enter new values only to replace them. Leave “Trust a new host key” off unless the server was reinstalled.':'Connect to Ubuntu or Debian. The first successful SSH connection pins its host key.'}</p><form onSubmit={submit} className="form-stack"><label>Server name<input required maxLength={100} value={name} onChange={e=>setName(e.target.value)} placeholder="wgwarden"/></label><label>Address<input required maxLength={255} value={host} onChange={e=>setHost(e.target.value)} placeholder="vpn.example.net"/></label><div className="field-row"><label>SSH port<input type="number" min="1" max="65535" required value={sshPort} onChange={e=>setSshPort(e.target.value)}/></label><label>SSH user<input required value={sshUser} onChange={e=>setSshUser(e.target.value)} placeholder="root"/></label></div><label>Password<input type="password" autoComplete="new-password" value={password} onChange={e=>{setPassword(e.target.value);if(e.target.value)setPrivateKey('')}} placeholder="Enter SSH password"/></label><label>Or private key<textarea rows={4} autoComplete="off" spellCheck={false} value={privateKey} onChange={e=>{setPrivateKey(e.target.value);if(e.target.value)setPassword('')}} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"/></label>{privateKey&&<label>Key passphrase<input type="password" autoComplete="off" value={passphrase} onChange={e=>setPassphrase(e.target.value)}/></label>}{editing&&<label className="checkbox-label"><input type="checkbox" checked={trustNewHostKey} onChange={e=>setTrustNewHostKey(e.target.checked)}/> Trust a new SSH host key (server was reinstalled)</label>}<p className="form-hint">{editing?'Leave both credential fields empty to reuse the saved encrypted credentials, or enter exactly one new credential.':'Enter exactly one SSH credential. It will be encrypted using the current application key.'}</p>{error&&<div className="form-error">{error}</div>}<div className="dialog-actions"><button type="button" className="button quiet" onClick={onClose}>Cancel</button><button className="button primary" disabled={busy}>{busy?<><span className="spinner"/> Checking SSH…</>:editing?'Save and reconnect':'Check & add server'}</button></div></form></>:<><div className="eyebrow">SSH CHECK COMPLETE</div><h2>{result.status==='ONLINE'?'Server connected':'Could not connect'}</h2><div className={`result-panel ${result.status.toLowerCase()}`}><StatusMark status={result.status}/><div><strong>{result.status}</strong><p>{result.status==='ONLINE'?`Detected OS: ${result.operatingSystem??'unknown'}`:result.connectionError??'SSH connection failed. Verify the host, credentials and host key.'}</p>{result.status==='ONLINE'&&result.hostKeyFingerprint&&<small className="mono">Host key {result.hostKeyFingerprint}</small>}</div></div><div className="dialog-actions"><button className="button primary" onClick={onClose}>Done</button></div></>}</section></div>
}

function PeerForm({token,serverId,onCreated}:{token:string;serverId:string;onCreated:(peer:PeerCreated)=>void}){
  const [name,setName]=useState('');const [trafficGb,setTrafficGb]=useState('');const [durationDays,setDurationDays]=useState('');const [showLimits,setShowLimits]=useState(false);const [error,setError]=useState('')
  const mutation=useMutation({mutationFn:()=>api<PeerCreated>(`/api/servers/${serverId}/peers`,token,{method:'POST',body:JSON.stringify({name,trafficLimitBytes:trafficGb?Math.round(Number(trafficGb)*1024*1024*1024):null,expiresAt:durationDays?new Date(Date.now()+Number(durationDays)*86400000).toISOString():null})}),onSuccess:onCreated,onError:e=>setError(e.message)})
  return <><form className="peer-form" onSubmit={e=>{e.preventDefault();setError('');mutation.mutate()}}><label>Device name<input autoFocus required maxLength={100} value={name} onChange={e=>setName(e.target.value)} placeholder="e.g. Laptop"/></label><div className="peer-form-actions"><button type="button" className={`button quiet ${trafficGb||durationDays?'limits-active':''}`} onClick={()=>setShowLimits(true)}>＋ {trafficGb||durationDays?'Edit limits':'Add limits'}</button><button className="button primary" disabled={mutation.isPending||!name.trim()}>{mutation.isPending?<><span className="spinner"/> Provisioning peer…</>:'Create one-time config'}</button></div>{error&&<div className="form-error">{error}</div>}</form>{showLimits&&<LimitsDialog trafficGb={trafficGb} durationDays={durationDays} onClose={()=>setShowLimits(false)} onSave={(traffic,days)=>{setTrafficGb(traffic);setDurationDays(days);setShowLimits(false)}}/>}</>
}

function LimitsDialog({trafficGb,durationDays,onClose,onSave}:{trafficGb:string;durationDays:string;onClose:()=>void;onSave:(traffic:string,days:string)=>void}){
  const [traffic,setTraffic]=useState(trafficGb);const [days,setDays]=useState(durationDays)
  return <div className="overlay" onMouseDown={e=>e.target===e.currentTarget&&onClose()}><section className="dialog panel limits-dialog"><button className="close" onClick={onClose}>×</button><div className="eyebrow">PEER / LIMITS</div><h2>Configure peer limits</h2><p className="dialog-copy">Limits are applied automatically by monitoring. Leave a field empty for unlimited usage.</p><div className="form-stack"><label>Traffic limit (GB)<input type="number" step="0.1" value={traffic} onChange={e=>setTraffic(e.target.value)} placeholder="Unlimited"/></label><label>Usage limit (days)<input type="number" step="1" value={days} onChange={e=>setDays(e.target.value)} placeholder="Unlimited"/></label></div><div className="dialog-actions"><button className="button quiet" onClick={onClose}>Cancel</button><button className="button primary" onClick={()=>onSave(traffic,days)}>Apply limits</button></div></section></div>
}

function ImportDialog({token,serverId,onClose,onImported}:{token:string;serverId:string;onClose:()=>void;onImported:()=>void}){
  const [interfaces,setInterfaces]=useState<DiscoveredInterface[]>([]);const [selected,setSelected]=useState('');const [peers,setPeers]=useState<string[]>([]);const [privateKeys,setPrivateKeys]=useState<Record<string,string>>({});const [busy,setBusy]=useState(true);const [saving,setSaving]=useState(false);const [error,setError]=useState('')
  const current=interfaces.find(item=>item.name===selected)
  useEffect(()=>{api<DiscoveredInterface[]>(`/api/servers/${serverId}/provisioning/discover`,token).then(value=>{setInterfaces(value);setSelected(value[0]?.name??'');setPeers(value[0]?.peers.map(peer=>peer.publicKey)??[])}).catch(e=>setError(e.message)).finally(()=>setBusy(false))},[serverId,token])
  function choose(name:string){const item=interfaces.find(value=>value.name===name);setSelected(name);setPeers(item?.peers.map(peer=>peer.publicKey)??[])}
  async function submit(){setSaving(true);setError('');try{await api(`/api/servers/${serverId}/provisioning/import`,token,{method:'POST',body:JSON.stringify({interfaceName:selected,peerPublicKeys:peers,peerPrivateKeys:privateKeys})});onImported()}catch(e){setError(e instanceof Error?e.message:'Import failed')}finally{setSaving(false)}}
  return <div className="overlay" onMouseDown={e=>e.target===e.currentTarget&&onClose()}><section className="dialog panel"><button className="close" onClick={onClose}>×</button><div className="eyebrow">SERVER SCAN / WIREGUARD</div><h2>Import existing resources</h2><p className="dialog-copy">Find active WireGuard interfaces and copy selected peers into wg-warden. Existing server configuration is not changed.</p>{busy?<div className="state-box">Scanning the server…</div>:error?<div className="form-error">{error}</div>:!interfaces.length?<div className="state-box">No active WireGuard interfaces found.</div>:<><label className="import-label">INTERFACE<select value={selected} onChange={e=>choose(e.target.value)}>{interfaces.map(item=><option key={item.name} value={item.name}>{item.name} · port {item.listenPort} · {item.subnet}</option>)}</select></label><div className="import-peers"><div className="import-label">PEERS TO IMPORT</div>{current?.peers.length?<>{current.peers.map(peer=><label className="import-peer" key={peer.publicKey}><input type="checkbox" checked={peers.includes(peer.publicKey)} onChange={e=>setPeers(old=>e.target.checked?[...old,peer.publicKey]:old.filter(key=>key!==peer.publicKey))}/><span><strong>{peer.name}</strong><small>{peer.allowedIp} · {peer.publicKey.slice(0,16)}…</small>{peers.includes(peer.publicKey)&&<input className="import-key" type="password" placeholder="Client private key (stored encrypted)" value={privateKeys[peer.publicKey]??''} onChange={e=>setPrivateKeys(old=>({...old,[peer.publicKey]:e.target.value}))}/>}</span></label>)}</>:<div className="state-box">No peers found on this interface.</div>}</div></>}<div className="dialog-actions"><button className="button quiet" onClick={onClose}>Cancel</button><button className="button primary" disabled={busy||saving||!selected} onClick={()=>void submit()}>{saving?<><span className="spinner"/> Importing…</>:'Import selected'}</button></div></section></div>
}

function PeerEditDialog({token,peer,onClose,onSaved}:{token:string;peer:Peer;onClose:()=>void;onSaved:()=>void}){
  const [name,setName]=useState(peer.name)
  const [privateKey,setPrivateKey]=useState('')
  const [trafficGb,setTrafficGb]=useState(peer.trafficLimitBytes?String(Math.round(peer.trafficLimitBytes/(1024*1024*1024))):'')
  const [durationDays,setDurationDays]=useState(()=>{
    if(!peer.expiresAt)return ''
    const ms=new Date(peer.expiresAt).getTime()-Date.now()
    return ms>0?String(Math.ceil(ms/86400000)):''
  })
  const [enabled,setEnabled]=useState(peer.enabled)
  const [error,setError]=useState('')
  const save=useMutation({mutationFn:(payload:object)=>api<Peer>(`/api/peers/${peer.id}`,token,{method:'PATCH',body:JSON.stringify(payload)}),onSuccess:onSaved,onError:e=>setError(e.message)})
  const config=useMutation({mutationFn:()=>api<PeerCreated>(`/api/peers/${peer.id}/config`,token),onSuccess:result=>{onClose();window.dispatchEvent(new CustomEvent('wg-warden-config',{detail:result}))},onError:e=>setError(e.message)})

  function submit(e:FormEvent){
    e.preventDefault()
    const trimmedName=name.trim()
    if(!trimmedName){setError('Peer name cannot be blank');return}
    const trafficLimitBytes=trafficGb.trim()?Math.round(Number(trafficGb)*1024*1024*1024):null
    const clearTrafficLimit=!trafficGb.trim()
    const expiresAtIso=durationDays.trim()?new Date(Date.now()+Number(durationDays)*86400000).toISOString():null
    const clearExpiresAt=!durationDays.trim()

    setError('')
    save.mutate({name:trimmedName,enabled,privateKey:privateKey.trim()||undefined,trafficLimitBytes,clearTrafficLimit,expiresAt:expiresAtIso,clearExpiresAt})
  }

  return <div className="overlay" onMouseDown={e=>e.target===e.currentTarget&&onClose()}><section className="dialog panel"><button className="close" onClick={onClose}>×</button><div className="eyebrow">PEER / EDIT</div><h2>Edit {peer.name}</h2><p className="dialog-copy">Update name, limits or private key. When limits are reached, the peer is disabled and can be extended here.</p><form onSubmit={submit} className="form-stack"><label>Device name<input value={name} onChange={e=>setName(e.target.value)} maxLength={100} required/></label><div className="field-row"><label>Traffic limit (GB)<input type="number" step="0.1" value={trafficGb} onChange={e=>setTrafficGb(e.target.value)} placeholder="Unlimited"/></label><label>Usage limit (days)<input type="number" step="1" value={durationDays} onChange={e=>{setDurationDays(e.target.value);if(!enabled&&e.target.value)setEnabled(true)}} placeholder="Unlimited"/></label></div><label>Client private key<input type="text" autoComplete="off" value={privateKey} onChange={e=>setPrivateKey(e.target.value)} placeholder="Stored encrypted; required to reopen the config"/></label><label className="checkbox-label"><input type="checkbox" checked={enabled} onChange={e=>setEnabled(e.target.checked)}/> Enable peer (allow connection)</label>{error&&<div className="form-error">{error}</div>}<div className="dialog-actions"><button type="button" className="button quiet" onClick={onClose}>Cancel</button><button type="button" className="button quiet" disabled={config.isPending} onClick={()=>config.mutate()}>Get config</button><button className="button primary" disabled={save.isPending||!name.trim()}>{save.isPending?<><span className="spinner"/> Saving…</>:'Save'}</button></div></form></section></div>
}

function PeerRow({peer,token,selected,onSelect,disabled,onChanged,onEdit,onRequestDelete}:{peer:Peer;token:string;selected:boolean;onSelect:()=>void;disabled:boolean;onChanged:()=>void;onEdit:()=>void;onRequestDelete:(action:()=>void)=>void}){
  const [error,setError]=useState('')
  const metrics=useQuery({queryKey:['peer-metrics',token,peer.id],queryFn:()=>api<PeerMetric[]>(`/api/peers/${peer.id}/metrics`,token),enabled:true})
  const latest=metrics.data?.at(-1)
  const toggle=useMutation({mutationFn:()=>api<Peer>(`/api/peers/${peer.id}`,token,{method:'PATCH',body:JSON.stringify({enabled:!peer.enabled})}),onSuccess:()=>{setError('');onChanged()},onError:e=>setError(e.message)})
  const remove=useMutation({mutationFn:()=>api<void>(`/api/peers/${peer.id}`,token,{method:'DELETE'}),onSuccess:()=>{setError('');onChanged()},onError:e=>setError(e.message)})
  const config=useMutation({mutationFn:()=>api<PeerCreated>(`/api/peers/${peer.id}/config`,token),onSuccess:result=>window.dispatchEvent(new CustomEvent('wg-warden-config',{detail:result})),onError:e=>setError(e.message)})
  const state=disabled||!peer.enabled?'OFFLINE':metrics.isError?'UNAVAILABLE':metrics.isLoading?'CHECKING':latest?.online?'ONLINE':'OFFLINE'
  return <>
    <tr className={selected?'selected':''} onClick={onSelect}><td><strong>{peer.name}</strong><small className="mono">{peer.publicKey.slice(0,16)}…</small>{peerLimits(peer)&&<small className="limit-note">{peerLimits(peer)}</small>}</td><td className="mono">{peer.allowedIp}</td><td><span className={`peer-state ${state.toLowerCase()}`}><i/>{state}</span></td><td>{metrics.isError?<span className="error-text">Unavailable</span>:metrics.isLoading?'Loading…':dateTime(latest?.lastHandshake??null)}</td><td className="mono traffic">↓ {bytes(latest?.rxBytes??0)}<br/>↑ {bytes(latest?.txBytes??0)}</td><td className="actions"><button disabled={disabled} onClick={e=>{e.stopPropagation();onEdit()}}>EDIT</button>{peer.configAvailable&&<button disabled={disabled||config.isPending} onClick={e=>{e.stopPropagation();config.mutate()}}>CONF</button>}{(()=>{const timeExpired=!peer.enabled&&!!peer.expiresAt&&new Date(peer.expiresAt).getTime()<=Date.now();const trafficExpired=!peer.enabled&&!!peer.trafficLimitBytes&&!!latest&&(latest.rxBytes+latest.txBytes)>=peer.trafficLimitBytes;const limitExpired=timeExpired||trafficExpired;return <button disabled={disabled||toggle.isPending||(!peer.enabled&&limitExpired)} title={limitExpired&&!peer.enabled?'Limit expired — edit limits to re-enable':peer.enabled?'Disable peer':'Enable peer'} onClick={e=>{e.stopPropagation();setError('');toggle.mutate()}}>{peer.enabled?'DISABLE':'ENABLE'}</button>})()}<button className="danger" disabled={disabled||remove.isPending} title="Delete peer" onClick={e=>{e.stopPropagation();onRequestDelete(()=>{setError('');remove.mutate()})}}>DELETE</button></td></tr>
    {error&&<tr><td colSpan={6} className="row-error">{error}</td></tr>}
  </>
}

function PeerChart({peer,data,loading,error}:{peer:Peer;data:PeerMetric[];loading:boolean;error:boolean}){
  const chart=data.map(p=>({...p,time:time(p.timestamp)}))
  return <section className="panel peer-chart"><PanelTitle title={`Peer traffic / ${peer.name}`} subtitle={`${peer.allowedIp} · ${peer.enabled?'enabled':'disabled'}`}/>{loading?<div className="chart-state">Loading peer history…</div>:error?<div className="chart-state error-text">Peer metrics could not be loaded.</div>:!chart.length?<div className="chart-state">No peer metrics collected yet.</div>:<div className="chart"><ResponsiveContainer width="100%" height="100%"><LineChart data={chart}><CartesianGrid stroke="#292c29" vertical={false}/><XAxis dataKey="time" tickLine={false} axisLine={false} tick={{fill:'#777d73',fontSize:10}} minTickGap={35}/><YAxis tickLine={false} axisLine={false} tick={{fill:'#777d73',fontSize:10}} tickFormatter={v=>bytes(Number(v))}/><Tooltip contentStyle={{background:'#181a18',border:'1px solid #3a4037',borderRadius:4,fontFamily:'monospace'}} formatter={v=>bytes(Number(v))}/><Line name="RX" dataKey="rxBytes" type="monotone" stroke="#a3e635" strokeWidth={1.8} dot={false}/><Line name="TX" dataKey="txBytes" type="monotone" stroke="#60a5fa" strokeWidth={1.8} dot={false}/></LineChart></ResponsiveContainer></div>}</section>
}

function ConfirmDialog({action,onClose,onConfirm}:{action:ConfirmAction;onClose:()=>void;onConfirm:()=>void}){
  return <div className="overlay confirm-overlay" onMouseDown={e=>e.target===e.currentTarget&&onClose()}><section className="dialog confirm-dialog panel"><div className="confirm-icon">!</div><div className="eyebrow">CONFIRM / DESTRUCTIVE ACTION</div><h2>{action.title}</h2><p className="dialog-copy">{action.message}</p><div className="dialog-actions"><button className="button quiet" onClick={onClose}>Cancel</button><button className="button danger solid-danger" onClick={onConfirm}>Delete</button></div></section></div>
}

function ConfigDialog({result,onClose}:{result:PeerCreated;onClose:()=>void}){
  const [copied,setCopied]=useState(false)
  const download=()=>{const link=document.createElement('a');link.href=URL.createObjectURL(new Blob([result.config],{type:'text/plain'}));link.download=`${result.peer.name.replace(/[^\p{L}\p{N}_-]/gu,'_')}.conf`;link.click();URL.revokeObjectURL(link.href)}
  async function copy(){await navigator.clipboard.writeText(result.config);setCopied(true);setTimeout(()=>setCopied(false),1800)}
  return <div className="overlay"><section className="dialog config-dialog panel"><button className="close" onClick={onClose}>×</button><div className="eyebrow">ONE-TIME SECRET / PEER</div><h2>Peer config created</h2><p className="dialog-copy">This config contains a private key and is shown once. Save the file or scan the QR now. Keys you import explicitly are stored encrypted so their config can be reopened.</p><div className="qr-wrap"><img src={`data:image/png;base64,${result.qrCodePngBase64}`} alt="WireGuard peer QR code"/><div><strong>{result.peer.name}</strong><code>{result.peer.allowedIp}</code><small>Private key is included in the QR and config.</small></div></div><textarea className="config-output" readOnly rows={9} style={{resize:'none'}} value={result.config}/><div className="dialog-actions"><button className="button quiet" onClick={copy}>{copied?'Copied':'Copy config'}</button><button className="button primary" onClick={download}>Download .conf</button><button className="button quiet" onClick={onClose}>Close</button></div></section></div>
}
