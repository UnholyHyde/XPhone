"""Read-only, identifier-free Android + Windows resource sampling.

UI state is selected separately. No settings, counters or apps are reset.
Requires psutil on the measurement PC, not in the shipped application.
"""
import argparse, json, re, subprocess, time
from pathlib import Path
import psutil

p=argparse.ArgumentParser()
p.add_argument('--adb',default='tools/adb.exe');p.add_argument('--serial',required=True)
p.add_argument('--scenario',required=True);p.add_argument('--output',required=True)
p.add_argument('--samples',type=int,default=6);p.add_argument('--interval',type=float,default=10)
p.add_argument('--warmup',type=float,default=15)
a=p.parse_args()
def shell(cmd):
 r=subprocess.run([a.adb,'-s',a.serial,'shell',cmd],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35)
 if r.returncode:raise RuntimeError('Android sampling failed: '+r.stderr[:180])
 return r.stdout
roles={'ru.xphone.booster':'hub','ru.xphone.booster:shell':'hub_shell','shizuku_server':'shizuku',
 'com.mobile.legends':'game','com.mobile.legends:UnityKillsMe':'game_aux','surfaceflinger':'compositor',
 'media.hwcodec':'hardware_codec','media.swcodec':'software_codec','samsung.software.media.c2@1.0-service':'vendor_codec'}
def discover():
 result={}
 for line in shell('ps -A -o PID,NAME').splitlines()[1:]:
  f=line.split()
  if len(f)!=2:continue
  pid,name=f
  if name in roles:result[pid]=roles[name]
  elif name=='app_process':
   cmd=shell('cat /proc/'+pid+'/cmdline')
   if 'com.genymobile.scrcpy.Server' in cmd:result[pid]='stream_server'
   elif 'com.genymobile.scrcpy.CleanUp' in cmd:result[pid]='stream_cleanup'
 return result
def pc():
 out={}
 for proc in psutil.process_iter(['name']):
  role={'xphone.exe':'pc_hub','xphone-screen.exe':'pc_stream','adb.exe':'pc_adb'}.get((proc.info['name'] or '').lower())
  if not role:continue
  try:
   cpu=proc.cpu_times();mem=proc.memory_info()
   out[str(proc.pid)]={'role':role,'cpu_seconds':cpu.user+cpu.system,'rss_bytes':mem.rss,
    'private_bytes':getattr(mem,'private',None),'started':proc.create_time()}
  except (psutil.NoSuchProcess,psutil.AccessDenied):pass
 return {'at':time.monotonic(),'processes':out}
def parse_mem(raw):
 out={}
 for key,label in [('pss_kib','TOTAL PSS'),('rss_kib','TOTAL RSS'),('swap_pss_kib','TOTAL SWAP PSS'),('graphics_kib','Graphics')]:
  m=re.search(r'(?m)(?:^|\s)'+label+r':\s*(\d+)',raw)
  if m:out[key]=int(m[1])
 return out
def take(mapping,memory=True):
 cmds=['echo __TIME; cat /proc/uptime; echo __SYSTEM; head -1 /proc/stat']
 for pid in mapping:cmds+=['echo __PROC_'+pid,'cat /proc/'+pid+'/stat 2>/dev/null']
 cmds+=['echo __FRAMES; dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | head -14',
  'echo __MEMORY; head -5 /proc/meminfo',
  'echo __BATTERY; dumpsys battery',
  'echo __THERMAL; dumpsys thermalservice',
  'echo __GPU; cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null',
  'echo __DISPLAY; dumpsys display | grep -m 1 mActiveSfDisplayMode',
  'echo __FOREGROUND; dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"']
 if memory:
  for pid in mapping:cmds+=['echo __PSS_'+pid,'dumpsys meminfo '+pid+' 2>/dev/null']
 raw=shell('; '.join(cmds));parts={}
 for m in re.finditer(r'(?m)^__(\w+)\r?\n([\s\S]*?)(?=^__\w+\r?\n|\Z)',raw):parts[m[1]]=m[2].strip()
 out={'device_uptime_s':float(parts['TIME'].split()[0]),'processes':{},'pc':pc()}
 system=parts.get('SYSTEM','').split()[1:9]
 if len(system)==8:out['system_ticks']=[int(x) for x in system]
 for pid,role in mapping.items():
  stat=parts.get('PROC_'+pid,'')
  if not stat:continue
  f=stat[stat.rfind(')')+2:].split()
  out['processes'][role]={'ticks':int(f[11])+int(f[12]),'child_ticks':int(f[13])+int(f[14]),'start':f[19],**parse_mem(parts.get('PSS_'+pid,''))}
 out['frames']={k:int(v) for k,v in re.findall(r'(?m)^(statsStart|totalFrames|missedFrames|clientCompositionFrames) = (\d+)',parts.get('FRAMES',''))}
 out['system_memory_kib']={k:int(v) for k,v in re.findall(r'(?m)^(MemTotal|MemFree|MemAvailable|Cached):\s+(\d+)',parts.get('MEMORY',''))}
 battery=parts.get('BATTERY','')
 out['battery']={k:int(v) for k,v in re.findall(r'(?m)^\s*(level|temperature|voltage|status|Charge counter):\s*(-?\d+)',battery)}
 out['usb_powered']='USB powered: true' in battery
 thermal=parts.get('THERMAL','');m=re.search(r'Thermal Status:\s*(\d+)',thermal)
 out['thermal_status']=int(m[1]) if m else None
 out['thermal_celsius']={name:float(value) for value,name in re.findall(r'Temperature\{mValue=([\d.]+), mType=\d+, mName=(AP|BAT|SKIN),',thermal)}
 mode=re.search(r'peakRefreshRate=([\d.]+)',parts.get('DISPLAY',''))
 out['display_mode_hz']=float(mode[1]) if mode else None
 gpu=parts.get('GPU','').split()
 if len(gpu)==2 and all(x.isdigit() for x in gpu):out['gpu_busy_raw']=[int(x) for x in gpu]
 fg=parts.get('FOREGROUND','');out['foreground']='hub' if 'ru.xphone.booster/' in fg else 'game' if 'com.mobile.legends/' in fg else 'other'
 return out
hz=int(shell('getconf CLK_TCK'));mapping=discover();time.sleep(a.warmup)
previous=take(mapping);samples=[]
for i in range(a.samples):
 time.sleep(max(0,a.interval))
 current=take(mapping);elapsed=current['device_uptime_s']-previous['device_uptime_s'];current['interval_s']=elapsed
 for role,item in current['processes'].items():
  old=previous['processes'].get(role)
  if old and old['start']==item['start']:
   item['cpu_one_core_pct']=(item['ticks']-old['ticks'])/hz/elapsed*100
   item['commands_cpu_one_core_pct']=(item['child_ticks']-old['child_ticks'])/hz/elapsed*100
  else:item['restart_or_new']=True
 if 'system_ticks' in current and 'system_ticks' in previous:
  delta=[x-y for x,y in zip(current['system_ticks'],previous['system_ticks'])];total=sum(delta)
  current['system_cpu_total_pct']=100*(1-(delta[3]+delta[4])/total) if total>0 else None
 f,oldf=current['frames'],previous['frames']
 if f.get('statsStart')==oldf.get('statsStart') and f.get('totalFrames',-1)>=oldf.get('totalFrames',0):
  current['composition_fps']=(f['totalFrames']-oldf['totalFrames'])/elapsed
 for pid,item in current['pc']['processes'].items():
  old=previous['pc']['processes'].get(pid)
  if old and old['started']==item['started']:item['cpu_one_core_pct']=100*(item['cpu_seconds']-old['cpu_seconds'])/(current['pc']['at']-previous['pc']['at'])
 # Do not save process IDs or wall-clock activity times in shareable data.
 raw_pc=current['pc'];current['pc']={'processes':[{k:v for k,v in item.items() if k!='started'} for item in raw_pc['processes'].values()]}
 samples.append(current)
 # Retain the unredacted in-memory PC counter snapshot for the next delta only.
 previous=current.copy();previous['pc']=raw_pc
 print('sample',i+1,'/',a.samples,flush=True)
result={'schema':1,'scenario':a.scenario,'android_version':'1.17.6','clock_ticks':hz,
 'warmup_s':a.warmup,'pc_logical_cores':psutil.cpu_count(),'samples':samples,
 'notes':['100% process CPU = one logical core. System CPU = percentage of all device cores.',
 'Observer uses ADB and meminfo; included in system load. Memory collection is sequential.',
 'USB powered: battery consumption and battery life cannot be inferred.',
 'FPS = whole-screen composition, not unique game frames. Fixed settings scene, not a match.',
 'Shared codec/compositor CPU is reported separately, not all attributable to XPhone.']}
out=Path(a.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('Saved',a.output,flush=True)
