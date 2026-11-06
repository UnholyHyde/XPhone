"""Write the Russian report and anonymized time series from completed captures."""
import json,statistics
from pathlib import Path
root=Path(__file__).resolve().parents[2];folder=root/'docs/measurements/1.17.6';source=root/'dist/bench-1.17.6'
data=json.loads((folder/'summary.json').read_text());by={r['scenario']:r for r in data}
labels={'game-handle-stream':'Язычок + стрим','game-panel-stream':'Большая панель + стрим','game-huds-stream':'Два мини-окна + стрим',
 'game-huds-no-stream':'Два мини-окна, без стрима','game-huds-stream-repeat':'Два мини-окна + стрим, повтор',
 'hub-stream':'Хаб + стрим','game-service-off-stream':'Служба выключена + стрим',
 'game-service-off-no-stream':'Служба выключена, без стрима','game-restored-stream':'Восстановленный язычок + стрим'}
rows=[by[k] for k in labels if k in by]
def fmt(x):return '—' if x is None else f'{x:.2f}'.replace('.',',')
def mean(x):return x['mean'] if x else None
def interval(x):return '—' if not x else f"{fmt(x['min'])}–{fmt(x['max'])}"
lines=['# Ресурсы XPhone 1.17.6: телефон и USB-трансляция','',
 'Измерено 6 ноября 2026 на одном подключённом устройстве Samsung. Это анализ ресурсов в фиксированной сцене, **не подтверждение ускорения игры** и не стресс-тест настоящего матча. Идентификаторы устройства и данные аккаунтов не сохранены в публикуемых материалах.','',
 '## Условия и методика','',
 '- Android-приложение 1.17.6; Android 17 / SDK 37. Открытый Windows-клиент — **1.4.1**, не версия исходников 1.4.2.',
 '- Mobile Legends: одна и та же страница основных настроек с анимированным предпросмотром. После переходов через хаб страница открывалась заново. Матч не запускался; игровая нагрузка здесь ниже и иная, чем в бою.',
 '- Экран 1440×3120, системный режим 120 Гц; хаб фактически оставался в горизонтальном положении. Сцены хаба и игры не равны по нагрузке.',
 '- USB; max-size 2336, ограничение потока 60 FPS, битрейт до 30 Мбит/с; playback-аудио, видеобуфер 0 мс, аудиобуфер 30 мс. В сцене не было активного голосового чата. Значения лимитов не являются измеренной скоростью или FPS потока.',
 '- Перед каждой серией 15 секунд стабилизации, затем 6 интервалов примерно по 11 секунд. Фактическая продолжительность указана в таблице; сбор памяти занимает дополнительное время.',
 '- Память Android — PSS, графические буферы уже включены. Swap PSS указан отдельно в JSON, его нельзя просто складывать с физической RAM. Общий Shizuku и системные службы выделены отдельно.',
 '- CPU процессов: **100% = одно логическое ядро**; включены завершившиеся дочерние команды. Системный CPU — доля всех ядер телефона. Это разные шкалы.',
 '- Windows: Working Set и Private Bytes измерены отдельно. На ПК 12 логических процессоров, поэтому CPU процесса в шкале одного ядра делится на 12 для шкалы всей машины.',
 '- Не завершались посторонние приложения. Сборщик ADB/meminfo сам создаёт некоторую нагрузку, включённую в системные счётчики; она не вычтена и отдельно не калибрована.',
 '- Выключение игровой службы восстанавливает профильные настройки. Поэтому контроль «служба выключена» показывает пользовательский сценарий, а не чистый эксперимент с единственной изменённой переменной.',
 '- После переподключения окно ПК стало крупнее: примерно 935×463 → 1824×873. Стоимость рендеринга Windows нельзя сравнивать как эксперимент с одинаковым размером окна.',
 '- В первой серии передний план подтверждён снимком; старое имя поля Android давало `other`. Последующие серии дополнительно проверены по `topResumedActivity`. Незавершённая серия с другим меню остановлена и не включена.','',
 '## Android: XPhone и отдельные процессы трансляции','',
 '| Сценарий | Серия, с | XPhone PSS, МиБ | Диапазон | CPU XPhone, % ядра | Стрим PSS, МиБ | CPU стрима, % ядра |',
 '|---|---:|---:|---:|---:|---:|---:|']
for r in rows:
 x=r['roles']['xphone'];v=r['roles']['stream'];lines.append(f"| {labels[r['scenario']]} | {fmt(r['duration_s'])} | {fmt(mean(x['pss_mib']))} | {interval(x['pss_mib'])} | {fmt(x['cpu_one_core_pct'])} | {fmt(mean(v['pss_mib']))} | {fmt(v['cpu_one_core_pct'])} |")
lines+=['','В строках выключенной службы остаются кэшированные процессы XPhone. Это не потребление удалённого приложения и не доказательство утечки. Прочерк в колонках стрима означает отсутствие этих процессов. Средние PSS рассчитаны по шести срезам, CPU — с весом по длительности интервала.','',
 '![Память Android](phone-ram.png)','', '![CPU процессов Android](phone-cpu.png)','',
 '## Что добавляет трансляция','',
 'Сервер scrcpy и его процесс-помощник занимают отдельную память на телефоне. Но их CPU **не описывает весь тракт**: кодирование и композиция выполняются также общими системными службами. Эти службы нельзя целиком приписывать XPhone.','',
 '| Сценарий | CPU аппаратного кодека, % ядра | CPU композитора, % ядра | GPU телефона, % | Системный CPU, % всех ядер |',
 '|---|---:|---:|---:|---:|']
for r in rows:
 lines.append(f"| {labels[r['scenario']]} | {fmt(r['roles']['hardware_codec']['cpu_one_core_pct'])} | {fmt(r['roles']['compositor']['cpu_one_core_pct'])} | {fmt(mean(r['gpu_pct']))} | {fmt(mean(r['system_cpu_pct']))} |")
lines+=['','GPU — среднее доступных точечных окон `gpubusy`, не интегральная загрузка всей минуты. Нулевой знаменатель означает отсутствие значения, не 0% нагрузки. Общий CPU колебался и без стрима; простое вычитание двух строк не даёт достоверной полной цены трансляции.','',
 '## Windows','',
 '![Ресурсы ПК](pc-load.png)','',
 '| Сценарий | Клиент: Working Set, МиБ | Окно: Working Set, МиБ | Окно: Private Bytes, МиБ | CPU окна, % ядра | CPU окна, % всего ПК |',
 '|---|---:|---:|---:|---:|---:|']
for r in rows:
 w=r['pc']['pc_stream'];cpu=mean(w['cpu_one_core_pct']);lines.append(f"| {labels[r['scenario']]} | {fmt(mean(r['pc']['pc_hub']['working_set_mib']))} | {fmt(mean(w['working_set_mib']))} | {fmt(mean(w['private_mib']))} | {fmt(cpu)} | {fmt(cpu/r['pc_logical_cores'] if cpu is not None else None)} |")
gpu_path=source/'pc-gpu-stream-samples.json'
if gpu_path.exists():
 gpu=json.loads(gpu_path.read_text(encoding='utf-8-sig'));(folder/'pc-gpu.json').write_text(json.dumps(gpu,ensure_ascii=False,indent=2),encoding='utf-8')
 lines+=['','Дополнительные шесть срезов GPU процесса окна, два мини-окна и стрим включены:','', '| Движок | Среднее, % | Диапазон, % |','|---|---:|---:|']
 for engine in ['3D','VideoDecode','VideoEncode']:
  values=[sum(v['percent'] for v in x['engines'] if v['engine']==engine) for x in gpu]
  lines.append(f'| {engine} | {fmt(statistics.mean(values))} | {fmt(min(values))}–{fmt(max(values))} |')
 dedicated=[v['DedicatedUsage']/1048576 for x in gpu for v in x['memory']];shared=[v['SharedUsage']/1048576 for x in gpu for v in x['memory']]
 if dedicated:lines+=['',f'Выделенная видеопамять окна: около {fmt(statistics.mean(dedicated))} МиБ; разделяемая GPU-память {fmt(min(shared))}–{fmt(max(shared))} МиБ. Эти величины не складываются с Working Set как независимая физическая память. Проценты разных GPU-движков также не суммируются в «общий GPU».']
lines+=['','## Кадры, температура и размеры','',
 '| Сценарий | Композиция экрана, кадр/с | Батарея, °C | Тепловой статус Android |','|---|---:|---:|---|']
for r in rows:lines.append(f"| {labels[r['scenario']]} | {fmt(mean(r['composition_fps']))} | {interval(r['battery_celsius'])} | {', '.join(map(str,r['thermal_status']))} |")
thermal_values={'AP':[],'SKIN':[]}
for path in source.glob('*.json'):
 raw=json.loads(path.read_text(encoding='utf-8-sig'))
 if not isinstance(raw,dict) or 'scenario' not in raw:continue
 for sample in raw['samples']:
  for sensor in thermal_values:
   if sensor in sample.get('thermal_celsius',{}):thermal_values[sensor].append(sample['thermal_celsius'][sensor])
for sensor,values in thermal_values.items():
 if values:lines+=['',f'Системный датчик {sensor}: {fmt(min(values))}–{fmt(max(values))} °C. Это значения, возвращённые thermalservice (могут быть кэшированы), не независимо измеренная температура кристалла.']
lines+=['','Глобальный `SurfaceFlinger totalFrames` не является счётчиком уникальных кадров игры или доставленных кадров scrcpy. В одной серии с выключенной службой и включённым стримом он показал около 119 кадров/с, тогда как другие игровые серии — около 90. Нельзя превращать это в вывод «оверлей отнимает 30 FPS»: нужно отдельно исследовать кадры игрового слоя, виртуальный захват, режимы композиции и влияние переходов приложения. Показатель не используется здесь как доказательство падения или прироста игрового FPS.','',
 'Телефон весь прогон питался по USB. Тепловой статус оставался 0; это отсутствие сообщения о троттлинге в данных, а не доказательство отсутствия любых ограничений частот. Батарея и AP/корпус — разные датчики. Расход батареи в час, автономность и прибавка температуры именно от XPhone этим прогоном не определены.','',
 'APK в локальной папке сборки: 1 004 326 байт (около 0,96 МиБ), без данных/кэша установленного приложения. Папка поставки Windows: 173 705 005 байт (около 165,66 МиБ), включая движок и среду выполнения. Это размеры конкретных локальных файлов, не размер каждого скачивания или установки.','',
 '## Выводы и приоритеты','',
 '1. Хаб на статичном экране требует заметно меньше CPU, чем активная игровая телеметрия. Закрытие большой панели экономит графическую память, но не выключает сбор показателей.',
 '2. При одном язычке большая часть измеренного CPU XPhone относится к вспомогательным командам. В `Overlay.showHandle` сохраняется подписка `warmMetrics`; `TelemetryFeed` опрашивает все поля каждые две секунды. Это подтверждённая точка для оптимизации; стоимость каждой команды отдельно ещё не профилировалась.',
 '3. Первое изменение для слабых устройств: управлять набором и частотой измерений по видимым окнам, сохранив быстрый показ кэшированных значений. Затем отдельно проверить аллокации панелей и буферы. В ходе замеров код и частоты опроса не менялись.',
 '4. Стрим имеет собственную цену и на телефоне, и на ПК; аппаратные кодек/декодер не означают нулевую нагрузку. Рекомендовать настройки слабому телефону следует после реального теста на нём.',
 '5. Изменения PSS после прогрева, закрытия панели и переподключения не доказывают утечку. Нужен отдельный длительный повторяемый тест. Глобальный счётчик кадров требует отдельной проверки до заявлений о влиянии оверлея на игру.','',
 '## Что не измерено и не включено в результаты','',
 'Реальный матч/тяжёлая сцена и 1% low игры; доставленные FPS и потери кадров потока; сквозная задержка изображения/касания/звука; Wi-Fi/LAN и сетевой битрейт; запись экрана одновременно со стримом; активные касания и другие приложения в окнах; Full Focus и все профили питания; автономность; часовой тест утечек и тепловой стресс; слабые устройства и другие модели. Их нельзя заменять нулями или считать проверенными по этому отчёту.','',
 '## Данные и повторение','',
 '[Сводный CSV](summary.csv) · [Полная сводка JSON](summary.json) · [Условия](conditions.json) · [GPU ПК](pc-gpu.json). В `series/` сохранены обезличенные интервалы без PID, абсолютных часов, идентификаторов и содержимого экрана. Исходные локальные захваты остаются в игнорируемой папке `dist/bench-1.17.6`.','',
 'Сборщик: `docs/tools/benchmark_resources.py` (Python + psutil), агрегация: `summarize_benchmark.py`, диаграммы: `plot_benchmark.py`, отчёт: `write_benchmark_report.py`. Перед серией вручную установите и визуально подтвердите нужный экран. При смене сцены/процесса серия требует повторения. В итоговом состоянии трансляция и игровая служба включены; тестовые мини-окна выключены.']
(folder/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
series=folder/'series';series.mkdir(exist_ok=True)
for path in source.glob('*.json'):
 d=json.loads(path.read_text(encoding='utf-8-sig'))
 if not isinstance(d,dict) or 'scenario' not in d:continue
 elapsed=0;clean=[]
 for s in d['samples']:
  elapsed+=s['interval_s'];item={k:v for k,v in s.items() if k not in ['device_uptime_s','system_ticks','frames','pc','processes','battery']}
  item['elapsed_s']=round(elapsed,3)
  item['processes']={role:{k:v for k,v in p.items() if k not in ['ticks','child_ticks','start']} for role,p in s['processes'].items()}
  item['pc']={'processes':[{k:v for k,v in p.items() if k not in ['started','cpu_seconds']} for p in s['pc']['processes']]}
  item['battery_temperature_tenths_c']=s['battery'].get('temperature');clean.append(item)
 result={'scenario':d['scenario'],'samples':clean,'notes':d['notes']}
 (series/path.name).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('Wrote report and anonymized series for',len(rows),'scenarios.')
