"""Deterministic opaque redaction/annotation. Originals remain only in ignored dist."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import math
ROOT=Path(__file__).resolve().parents[2];PRIVATE=ROOT/'dist/readme-private';OUT=ROOT/'docs/images/guide'
FONT='C:/Windows/Fonts/segoeui.ttf';BOLD='C:/Windows/Fonts/segoeuib.ttf'
def font(n,bold=False):return ImageFont.truetype(BOLD if bold else FONT,n)
def wrap(draw,text,f,width):
 out=[];line=''
 for word in text.split():
  nxt=(line+' '+word).strip()
  if draw.textlength(nxt,font=f)>width and line:out.append(line);line=word
  else:line=nxt
 if line:out.append(line)
 return out

def rect_phone(box):
 x1,y1,x2,y2=box
 return (round((x1-1)*1440/431),round((y1-31)*3120/934),round((x2-1)*1440/431),round((y2-31)*3120/934))
def mask(im,box,label=None):
 draw=ImageDraw.Draw(im);draw.rectangle(box,fill='#15151C')
 if label:
  f=font(31);width=box[2]-box[0];lines=wrap(draw,label,f,width-24);height=len(lines)*40
  for i,line in enumerate(lines):draw.text(((box[0]+box[2]-draw.textlength(line,font=f))/2,(box[1]+box[3]-height)/2+i*40),line,font=f,fill='#BDB7CD')

def safe_phone(name):
 im=Image.open(PRIVATE/(name+'.png')).convert('RGB')
 if im.size!=(1440,3120):raise ValueError((name,im.size))
 if name in ['hub','profile','panel-settings']:
  mask(im,rect_phone((170,205,275,242)),'Скрыто')
 if name=='hub':
  mask(im,rect_phone((35,268,340,345)),'Личный профиль скрыт')
  mask(im,rect_phone((35,435,199,546)),'Скрыто')
  mask(im,rect_phone((35,637,199,746)),'Скрыто')
 if name=='profile':
  mask(im,rect_phone((28,298,406,571)),'Личное игровое время и график скрыты')
 return im.crop((0,140,1440,3060))

def board(name,title,subtitle,notes):
 im=safe_phone(name);canvas=Image.new('RGB',(1400,1120),'#0E0F14');d=ImageDraw.Draw(canvas)
 d.text((50,30),'PHONE X / РУКОВОДСТВО',font=font(16,True),fill='#AD96EE');d.text((50,64),title,font=font(35,True),fill='#F1F0F6');d.text((50,113),subtitle,font=font(18),fill='#A4A5B5')
 scale=440/im.width;phone=im.resize((440,round(im.height*scale)),Image.Resampling.LANCZOS);x,y=50,165
 canvas.paste(phone,(x,y));d.rounded_rectangle((x-1,y-1,x+phone.width,y+phone.height),radius=14,outline='#3B3549',width=2)
 for i,(target,heading,body) in enumerate(notes,1):
  nx,ny=555,185+(i-1)*225
  d.rounded_rectangle((nx,ny,nx+785,ny+193),radius=16,fill='#191820',outline='#3B3549',width=1)
  d.ellipse((nx+18,ny+19,nx+52,ny+53),fill='#A991FF');d.text((nx+28,ny+22),str(i),font=font(19,True),fill='#14101E')
  d.text((nx+70,ny+20),heading,font=font(23,True),fill='#F2F0F7')
  for j,line in enumerate(wrap(d,body,font(21),680)):d.text((nx+70,ny+61+j*30),line,font=font(21),fill='#B8B6C5')
  px,py=target;rawx=(px-1)*1440/431;rawy=(py-31)*3120/934
  tx=x+rawx*scale;ty=y+(rawy-140)*scale
  d.line((nx,ny+36,510,ny+36,tx,ty),fill='#BEABFF',width=3);d.ellipse((tx-13,ty-13,tx+13,ty+13),fill='#A991FF');d.text((tx-5,ty-11),str(i),font=font(16,True),fill='#14101E')
 d.text((555,1020),'Реальный интерфейс · личные поля закрыты',font=font(17),fill='#A4A5B5');d.text((555,1050),'Без курсора Windows · без кодов подключения',font=font(17),fill='#A4A5B5')
 canvas.save(OUT/(name+'.png'),optimize=True)
 return im
OUT.mkdir(parents=True,exist_ok=True)
board('hub','Библиотека игр','Запуск и настройки — разные действия',[( (320,582),'Играть','Широкая кнопка запускает приложение.'),((320,485),'Карточка открывает профиль','Нажмите иконку, название или свободную область вне кнопки.'),((316,720),'Добавить игру','Выберите приложение в списке. Остальные карточки и личный профиль на кадре скрыты.')])
board('profile','Профиль отдельной игры','Настройки сохраняются сразу',[( (212,430),'График семи дней','Здесь находятся время и сессии. Личные значения полностью скрыты на иллюстрации.'),((367,593),'Свой профиль','Автодействия и состав кнопок задаются отдельно для этой игры.'),((214,710),'Готово — закрыть окно','Закрытие назад тоже сохраняет изменения. Удаление из библиотеки не удаляет игру.')])
board('buttons','Состав игрового оверлея','Панель → Кнопки оверлея',[( (365,254),'Показывать или скрыть','Выключите ненужные плитки. Это общий набор кнопок.'),((365,310),'Оставить FPS / Гц','Можно оставить лишь нужные элементы. Профили игры настраиваются отдельно.'),((365,478),'Возможности зависят от телефона','Недоступные действия не обязаны присутствовать. Скрытие плитки не выключает работающую функцию.')])
board('panel-settings','Окно FPS / Гц','Настройки окна находятся во вкладке «Панель»',[( (367,480),'Показать окно','FPS и частота дисплея — отдельное плавающее окно.'),((367,527),'Закрепить','Закреплённое окно пропускает нажатия в игру.'),((213,617),'Непрозрачность и подложка','Перетаскивайте незакреплённое окно. Для совместимости с касаниями есть ограничения непрозрачности.')])
# Cursor-free phone captures are redacted before assembling any animation.
frames=[]
for name,label in [('hub','Библиотека'),('panel-settings','Окно FPS / Гц'),('buttons','Состав оверлея')]:
 im=safe_phone(name);im.thumbnail((410,830),Image.Resampling.LANCZOS);f=Image.new('RGB',(450,920),'#101116');dd=ImageDraw.Draw(f);dd.text((20,15),label,font=font(22,True),fill='#E8E5EF');f.paste(im,((450-im.width)//2,65));dd.text((20,890),'Обзор экранов · данные скрыты',font=font(15),fill='#B7B0C5');frames.append(f)
frames[0].save(OUT/'hub-tour.gif',save_all=True,append_images=frames[1:],duration=[2400]*3,loop=0,optimize=True,disposal=2)
# Desktop source is bounded to XPhone; identifiers are flattened into pixels.
im=Image.open(PRIVATE/'desktop-main.png').convert('RGB')
for box in [(76,145,282,188),(405,111,660,135),(20,528,370,553),(20,653,370,682)]:mask(im,box)
im=im.crop((0,31,im.width,im.height));canvas=Image.new('RGB',(1400,990),'#101116');d=ImageDraw.Draw(canvas)
d.text((40,28),'Дополнение для Windows',font=font(34,True),fill='#EEEAF5');d.text((40,83),'Подключение · картинка · звук',font=font(20),fill='#B8A7EB');canvas.paste(im,(35,145))
for y,head,body in [(170,'1. Подключение','Выбор телефона и запуск окна. Данные устройства скрыты.'),(395,'2. Картинка','Качество, лимит FPS и битрейт. Проверяйте реальные возможности канала.'),(620,'3. Звук','Куда передавать аудио. Захват источника зависит от Android.')]:
 d.text((1000,y),head,font=font(22,True),fill='#EDE8F5')
 for j,line in enumerate(wrap(d,body,font(20),355)):d.text((1000,y+47+j*29),line,font=font(20),fill='#B5B2C2')
d.text((40,935),'Реальный интерфейс · имя и модель устройства скрыты · без QR/серийных номеров',font=font(18),fill='#A7A3B2');canvas.save(OUT/'desktop-main.png',optimize=True)
print('Created four annotated phone screenshots, desktop overview and redacted overview GIF.')
# Landscape capture: game settings provide a backdrop without accounts or contacts.
im=Image.open(PRIVATE/'overlay.png').convert('RGB')
# Remove the server timestamp, including its portion behind the translucent header.
mask(im,(2110,32,2830,88))
canvas=Image.new('RGB',(1600,1090),'#101116');d=ImageDraw.Draw(canvas)
d.text((40,25),'Игровая панель: всё по краям',font=font(35,True),fill='#F0ECF7')
d.text((40,80),'Реальный кадр поверх настроек игры · без курсора и личных данных',font=font(20),fill='#B7AFCA')
im.thumbnail((1520,702),Image.Resampling.LANCZOS);canvas.paste(im,(40,130))
for x,head,body in [(40,'1. Показатели сверху','Нажмите значение: откроются подробности. FPS и Гц — разные показатели.'),(570,'2. Действия по сторонам','Нажатие переключает действие. Удержание открывает доступные настройки.'),(1100,'3. Шестерёнка и крестик','Настройте панель или закройте её. Язычок остаётся доступен.')]:
 d.text((x,865),head,font=font(23,True),fill='#C5B2FF')
 for j,line in enumerate(wrap(d,body,font(21),450)):d.text((x,910+j*30),line,font=font(21),fill='#BDB9C9')
canvas.save(OUT/'overlay.png',optimize=True)
