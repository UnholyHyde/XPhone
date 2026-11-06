"""Aggregate only completed benchmark series; never substitute missing data with zero."""
import csv,json,statistics
from pathlib import Path
root=Path(__file__).resolve().parents[2];source=root/'dist/bench-1.17.6';out=root/'docs/measurements/1.17.6'
out.mkdir(parents=True,exist_ok=True)
def stats(values):
 values=[x for x in values if x is not None]
 return {'mean':round(statistics.mean(values),3),'min':round(min(values),3),'max':round(max(values),3)} if values else None
def roles(s,names):return [v for k,v in s['processes'].items() if k in names]
def metric(samples,names,key,scale=1):
 values=[]
 for s in samples:
  items=roles(s,names)
  values.append(sum(x[key] for x in items)/scale if items and all(key in x for x in items) else None)
 return stats(values)
def cpu(samples,names):
 duration=0;total=0
 for s in samples:
  items=roles(s,names)
  if not items or any('cpu_one_core_pct' not in x for x in items):continue
  duration+=s['interval_s'];total+=s['interval_s']*sum(x['cpu_one_core_pct']+x.get('commands_cpu_one_core_pct',0) for x in items)
 return round(total/duration,3) if duration else None
def pc_metric(samples,names,key,scale=1):
 return stats([sum(x[key] for x in s['pc']['processes'] if x['role'] in names and x.get(key) is not None)/scale
  if any(x['role'] in names and x.get(key) is not None for x in s['pc']['processes']) else None for s in samples])
results=[]
for path in sorted(source.glob('*.json')):
 d=json.loads(path.read_text(encoding='utf-8-sig'))
 if not isinstance(d,dict) or 'scenario' not in d:continue
 s=d['samples'];r={'scenario':d['scenario'],'samples':len(s),'duration_s':round(sum(x['interval_s'] for x in s),2),
  'foreground_states':sorted(set(x['foreground'] for x in s)),'pc_logical_cores':d['pc_logical_cores'],
  'system_cpu_pct':stats([x.get('system_cpu_total_pct') for x in s]),'composition_fps':stats([x.get('composition_fps') for x in s]),
  'battery_celsius':stats([x['battery'].get('temperature',0)/10 for x in s if 'temperature' in x['battery']]),
  'thermal_status':sorted(set(x['thermal_status'] for x in s)),
  'gpu_pct':stats([100*x['gpu_busy_raw'][0]/x['gpu_busy_raw'][1] for x in s if x.get('gpu_busy_raw',[0,0])[1]>0 and 0<=x['gpu_busy_raw'][0]<=x['gpu_busy_raw'][1]]),
  'mem_available_mib':stats([x['system_memory_kib']['MemAvailable']/1024 for x in s if 'MemAvailable' in x['system_memory_kib']]),
  'roles':{}}
 for label,names in {'xphone':['hub','hub_shell'],'stream':['stream_server','stream_cleanup'],'game':['game','game_aux'],
  'shizuku':['shizuku'],'compositor':['compositor'],'hardware_codec':['hardware_codec'],'software_codec':['software_codec']}.items():
  r['roles'][label]={'pss_mib':metric(s,names,'pss_kib',1024),'swap_pss_mib':metric(s,names,'swap_pss_kib',1024),
   'graphics_mib':metric(s,names,'graphics_kib',1024),'cpu_one_core_pct':cpu(s,names)}
 r['pc']={role:{'working_set_mib':pc_metric(s,[role],'rss_bytes',1048576),'private_mib':pc_metric(s,[role],'private_bytes',1048576),
  'cpu_one_core_pct':pc_metric(s,[role],'cpu_one_core_pct')} for role in ['pc_hub','pc_stream','pc_adb']}
 results.append(r)
(out/'summary.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
columns=['scenario','duration_s','xphone_pss_mib','xphone_cpu_one_core_pct','stream_pss_mib','stream_cpu_one_core_pct','pc_stream_working_set_mib','pc_stream_cpu_one_core_pct','system_cpu_pct','gpu_pct','composition_fps','battery_celsius']
def mean(v):return v['mean'] if v else ''
with (out/'summary.csv').open('w',encoding='utf-8-sig',newline='') as f:
 writer=csv.DictWriter(f,fieldnames=columns);writer.writeheader()
 for r in results:
  row={k:r[k] for k in ['scenario','duration_s']}
  for role in ['xphone','stream']:
   row[role+'_pss_mib']=mean(r['roles'][role]['pss_mib']);row[role+'_cpu_one_core_pct']=r['roles'][role]['cpu_one_core_pct']
  row['pc_stream_working_set_mib']=mean(r['pc']['pc_stream']['working_set_mib']);row['pc_stream_cpu_one_core_pct']=mean(r['pc']['pc_stream']['cpu_one_core_pct'])
  for k in ['system_cpu_pct','gpu_pct','composition_fps','battery_celsius']:row[k]=mean(r[k])
  writer.writerow(row)
print((out/'summary.csv').read_text(encoding='utf-8-sig'))
