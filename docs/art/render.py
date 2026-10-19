"""Renders the README banner from docs/art/icon.png. The icon is the source of truth; this only frames it.
Usage: python3 docs/art/render.py <clean_square_icon.png>  (the icon without its white corners)"""
import sys, cv2, numpy as np
from PIL import Image, ImageDraw, ImageFont
here = __file__.rsplit('/', 1)[0]
src = cv2.imread(sys.argv[1])
# sky plate: paint the stick out and stretch the sky across the banner
m = np.zeros(src.shape[:2], np.uint8); cv2.rectangle(m, (140, 475), (1310, 905), 255, -1)
sky = cv2.inpaint(src, m, 40, cv2.INPAINT_TELEA)
W, H = 1280, 440
sky = cv2.resize(sky[300:1100], (W, H), interpolation=cv2.INTER_AREA)
sky = cv2.GaussianBlur(sky, (0, 0), 2)
im = Image.fromarray(cv2.cvtColor(sky, cv2.COLOR_BGR2RGB)).convert('RGBA')
icon = Image.open(f'{here}/icon.png').convert('RGBA').resize((300, 300), Image.LANCZOS)
sh = Image.new('RGBA', (W, H), 0); d = ImageDraw.Draw(sh)
d.rounded_rectangle((96, 82, 96 + 300, 82 + 300), 48, fill=(0, 0, 0, 150))
from PIL import ImageFilter
im = Image.alpha_composite(im, sh.filter(ImageFilter.GaussianBlur(18)))
im.alpha_composite(icon, (90, 70))
d = ImageDraw.Draw(im)
F = '/usr/share/fonts/opentype/inter/'
d.text((446, 128), 'Raven Disk Utility', font=ImageFont.truetype(F + 'InterDisplay-Bold.otf', 72), fill='#F2EFEB')
d.text((450, 228), 'Format, partition and check external drives', font=ImageFont.truetype(F + 'Inter-Medium.otf', 30), fill='#E38D58')
d.text((450, 270), 'from your Android phone. No root, no PC.', font=ImageFont.truetype(F + 'Inter-Medium.otf', 30), fill='#E38D58')
im.convert('RGB').save(f'{here}/banner.png', optimize=True)
