"""Plot completed 1.17.6 scenarios without inventing missing measurements."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
root=Path(__file__).resolve().parents[2];folder=root/'docs/measurements/1.17.6'
rows=json.loads((folder/'summary.json').read_text(encoding='utf-8'))
labels={'game-handle-stream':'Язычок + стрим','game-panel-stream':'Панель + стрим','game-huds-stream':'Два мини-окна + стрим',
 'game-huds-no-stream':'Два мини-окна без стрима','game-huds-stream-repeat':'Два мини-окна + стрим (повтор)',
 'hub-stream':'Хаб + стрим','game-service-off-stream':'Служба выключена + стрим',
 'game-service-off-no-stream':'Служба выключена, без стрима','game-restored-stream':'Восстановленный язычок + стрим'}
order=list(labels);rows.sort(key=lambda x:order.index(x['scenario']) if x['scenario'] in order else 99)
plt.rcParams.update({'font.family':'DejaVu Sans','text.color':'#EEEAF5','axes.labelcolor':'#B6B1C1','xtick.color':'#B6B1C1','ytick.color':'#EEEAF5','svg.fonttype':'path'})
for kind,title,xlabel in [('ram','Память на телефоне','PSS, МиБ · графические буферы включены'),('cpu','CPU процессов на телефоне','% одного логического ядра · включая дочерние команды')]:
 fig,ax=plt.subplots(figsize=(13,7),dpi=150);fig.patch.set_facecolor('#101116');ax.set_facecolor('#101116');y=range(len(rows))
 def value(r,role):
  v=r['roles'][role]['pss_mib'] if kind=='ram' else r['roles'][role]['cpu_one_core_pct']
  return v['mean'] if isinstance(v,dict) else v
 for role,color,label,offset in [('xphone','#A991FF','XPhone со службой',-.18),('stream','#71C8C1','Сервер и помощник трансляции',.18)]:
  for i,r in enumerate(rows):
   v=value(r,role)
   if v is None:continue
   ax.barh(i+offset,v,height=.31,color=color,label=label if i==0 else None)
   ax.text(v+(.8 if kind=='ram' else .12),i+offset,f'{v:.1f}',va='center',fontsize=9)
 ax.set_yticks(list(y),[labels.get(r['scenario'],r['scenario']) for r in rows]);ax.invert_yaxis()
 ax.set_xlabel(xlabel,labelpad=14);ax.set_title(title+' · XPhone 1.17.6',loc='left',fontsize=19,pad=34,fontweight='bold')
 ax.set_xlim(0,230 if kind=='ram' else 15);ax.xaxis.grid(True,color='#37333F');ax.set_axisbelow(True)
 for spine in ax.spines.values():spine.set_visible(False)
 ax.tick_params(length=0,pad=10);ax.legend(loc='lower right',bbox_to_anchor=(1,1.01),ncol=2,facecolor='#211C2A',edgecolor='#40354D',labelcolor='#E8E0F0',fontsize=8)
 fig.subplots_adjust(left=.37,right=.96,top=.86,bottom=.19)
 fig.text(.04,.08,'Игровые серии — одна сцена; короткий тест. Нет столбца = процесс отсутствовал, а не неизвестное значение 0.',fontsize=9,color='#B6B1C1')
 fig.text(.04,.04,'Игра, Shizuku и общие службы кодека/композитора не включены. Выключенная служба оставляет кэш приложения.',fontsize=9,color='#B6B1C1')
 fig.savefig(folder/f'phone-{kind}.png',facecolor=fig.get_facecolor());plt.close(fig)
print('Saved phone RAM/CPU plots.')
example=next(r for r in rows if r['scenario']=='game-huds-stream')
gpu=json.loads((folder/'pc-gpu.json').read_text(encoding='utf-8'))
fig,axes=plt.subplots(1,2,figsize=(12,4.6),dpi=160);fig.patch.set_facecolor('#101116')
values=[example['pc'][role]['working_set_mib']['mean'] for role in ['pc_hub','pc_stream']]
axes[0].barh([0,1],values,color=['#A991FF','#71C8C1']);axes[0].set_yticks([0,1],['Оболочка XPhone','Окно трансляции']);axes[0].invert_yaxis();axes[0].set_xlim(0,210);axes[0].set_xlabel('Working Set, МиБ')
for i,v in enumerate(values):axes[0].text(v+3,i,f'{v:.1f}',va='center')
names=['3D','VideoDecode','VideoEncode'];values=[sum(sum(e['percent'] for e in s['engines'] if e['engine']==name) for s in gpu)/len(gpu) for name in names]
axes[1].barh(range(3),values,color='#71C8C1');axes[1].set_yticks(range(3),names);axes[1].invert_yaxis();axes[1].set_xlim(0,13);axes[1].set_xlabel('Загрузка отдельного GPU-движка, %')
for i,v in enumerate(values):axes[1].text(v+.2,i,f'{v:.2f}%',va='center')
for ax in axes:
 ax.set_facecolor('#101116');ax.xaxis.grid(True,color='#37333F');ax.set_axisbelow(True);ax.tick_params(length=0,pad=8)
 for spine in ax.spines.values():spine.set_visible(False)
fig.suptitle('На ПК · два мини-окна и USB-трансляция',x=.06,ha='left',fontsize=18,fontweight='bold')
fig.subplots_adjust(left=.17,right=.95,top=.78,bottom=.25,wspace=.62)
fig.text(.06,.10,'CPU окна: около 1% всего тестового ПК (12 логических процессоров). Данные коротких серий.',fontsize=10,color='#B6B1C1')
fig.text(.06,.04,'GPU-движки не суммируются. Видеопамять и Private Bytes приведены отдельно в полном отчёте.',fontsize=10,color='#B6B1C1')
fig.savefig(folder/'pc-load.png',facecolor=fig.get_facecolor());plt.close(fig)
