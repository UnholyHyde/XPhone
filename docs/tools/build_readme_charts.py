"""Reproduce README charts from the three confirmed, identifier-free measurements."""
import csv
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
root=Path(__file__).resolve().parents[2]
source=root/'docs/data/resources-1.15.0.csv'
rows=list(csv.DictReader(source.open(encoding='utf-8-sig')))
labels=['Хаб · после стабилизации','Игра · только язычок','Игра · открытая панель']
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':12,'text.color':'#E8E9EF','axes.labelcolor':'#BBC0CE','xtick.color':'#BBC0CE','ytick.color':'#E8E9EF','svg.fonttype':'path'})
out=root/'docs/images/guide';out.mkdir(parents=True,exist_ok=True)
for kind in ['ram','cpu']:
 fig,ax=plt.subplots(figsize=(12,4.8),dpi=160);fig.patch.set_facecolor('#101116');ax.set_facecolor('#101116')
 means=[float(r['ram_mean_mib'] if kind=='ram' else r['cpu_percent_one_core_including_commands']) for r in rows]
 ax.barh(range(3),means,height=.46,color=['#727787','#A991FF','#D0C4FF'])
 if kind=='ram':
  err=[[m-float(r['ram_min_mib']) for m,r in zip(means,rows)],[float(r['ram_max_mib'])-m for m,r in zip(means,rows)]]
  ax.errorbar(means,range(3),xerr=err,fmt='none',ecolor='#ECECF1',capsize=5,linewidth=1.5)
 for i,value in enumerate(means):
  label_x=float(rows[i]['ram_max_mib'])+9 if kind=='ram' else value+.28
  ax.text(label_x,i,f'{value:.1f}' if kind=='ram' else f'{value:.2f}%',va='center',fontweight='bold')
 ax.set_yticks(range(3),labels);ax.invert_yaxis();ax.set_xlim(0,260 if kind=='ram' else 11)
 ax.set_xlabel('Память PSS · МиБ · XPhone вместе со своей службой' if kind=='ram' else 'CPU · % одного логического ядра · процессы и дочерние команды',labelpad=15)
 ax.set_title('ПАМЯТЬ XPHONE' if kind=='ram' else 'НАГРУЗКА CPU XPHONE',loc='left',fontsize=19,fontweight='bold',pad=24)
 for spine in ax.spines.values():spine.set_visible(False)
 ax.xaxis.grid(True,color='#333744',alpha=.7);ax.set_axisbelow(True);ax.tick_params(axis='both',length=0,pad=10)
 fig.subplots_adjust(left=.29,right=.94,top=.79,bottom=.29)
 fig.text(.04,.94,'ИЗМЕРЕНО · ANDROID 1.15.0 · 3 СЦЕНАРИЯ',fontsize=10,color='#A991FF',weight='bold')
 fig.text(.04,.11,'Один тестовый телефон. Серии: 16 / 33,2 / 33,1 с. Не сравнение с другими продуктами.',fontsize=10,color='#A4A9B6')
 note='Хаб: первый срез основного процесса — 408,7 МиБ; на графике установившийся режим.' if kind=='ram' else '100% = одно занятое ядро, а не весь телефон. Системный CPU/GPU отдельно не измерен.'
 fig.text(.04,.055,note,fontsize=9,color='#A4A9B6')
 fig.savefig(out/f'resources-{kind}.png',facecolor=fig.get_facecolor());fig.savefig(out/f'resources-{kind}.svg',facecolor=fig.get_facecolor());plt.close(fig)
print('Created RAM and CPU charts from three measured scenarios.')
