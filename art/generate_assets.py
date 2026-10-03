#!/usr/bin/env python3
"""Original Sum Path artwork, drawn from geometry; no third-party assets."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT.parents[1] / "outputs" / "store-assets"
CREAM, INK, TEAL, MINT, CORAL, AMBER = "#F7F3E9", "#182D46", "#176D78", "#D5EAE0", "#EF997D", "#F3C66B"
FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"
BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"

def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else FONT, size)

def center(draw, xy, text, size, color=INK, bold=True):
    draw.text(xy, text, fill=color, font=font(size, bold), anchor="mm")

def icon(size=512):
    scale = 3
    im = Image.new("RGB", (size*scale, size*scale), CREAM)
    d = ImageDraw.Draw(im)
    q = size*scale/108
    active = {(0,0),(1,0),(1,1),(2,1),(2,2),(3,2),(3,3)}
    for r in range(4):
        for c in range(4):
            x,y = (19+c*18)*q,(19+r*18)*q
            d.rounded_rectangle((x,y,x+16*q,y+16*q), radius=3*q, fill=MINT if (c,r) in active else "#E5E6DF")
    points = [((27+c*18)*q,(27+r*18)*q) for c,r in [(0,0),(1,0),(1,1),(2,1),(2,2),(3,2),(3,3)]]
    d.line(points, fill=TEAL, width=round(4.8*q), joint="curve")
    for x,y in points: d.ellipse((x-2.4*q,y-2.4*q,x+2.4*q,y+2.4*q),fill=TEAL)
    for (c,r),v in [((0,0),"2"),((1,0),"3"),((2,1),"4"),((2,2),"1"),((3,3),"5")]:
        x,y=(27+c*18)*q,(27+r*18)*q
        d.ellipse((x-6.9*q,y-6.9*q,x+6.9*q,y+6.9*q),fill=TEAL if (c,r)!=(3,3) else INK)
        center(d,(x,y+.25*q),v,round(10*q),CREAM)
    x,y=45*q,45*q
    d.polygon([(x,y-7.5*q),(x+7.5*q,y),(x,y+7.5*q),(x-7.5*q,y)],fill=AMBER)
    d.ellipse((x-2.1*q,y-2.1*q,x+2.1*q,y+2.1*q),fill=INK)
    return im.resize((size,size), Image.Resampling.LANCZOS)

def feature(language):
    scale=2
    im = Image.new("RGB",(1024*scale,500*scale),CREAM)
    d=ImageDraw.Draw(im)
    def t(x,y,text,size,color=INK,bold=False): d.text((x*scale,y*scale),text,fill=color,font=font(size*scale,bold))
    d.rounded_rectangle((56*scale,48*scale,226*scale,82*scale),radius=17*scale,fill=MINT)
    center(d,(141*scale,65*scale),"OFFLINE" if language=="en" else "ОФЛАЙН",15*scale,TEAL)
    title = "Sum Path" if language=="en" else "Сумма-путь"
    t(52,117,title,58,bold=True)
    t(56,207,"Find your route." if language=="en" else "Найди свой путь.",32,bold=True)
    t(56,251,"Reach the exact sum." if language=="en" else "Собери точную сумму.",28)
    t(56,365,"Numbers · Keys · Your solution" if language=="en" else "Числа · Ключи · Твоё решение",19,TEAL)
    # An illustrative original valid route: 2+3+4+1+3+2+1 = 16; both keys visited.
    vals=[[2,3,7,2],[6,4,1,5],[2,8,3,2],[5,3,6,1]]
    path=[(0,0),(1,0),(1,1),(2,1),(2,2),(3,2),(3,3)]
    bx,by,step,cell=627,70,82,72
    for r in range(4):
        for c in range(4):
            x,y=(bx+c*step)*scale,(by+r*step)*scale
            d.rounded_rectangle((x,y,x+cell*scale,y+cell*scale),radius=15*scale,fill=MINT if (c,r) in path else "#E5E6DF")
    pts=[((bx+c*step+cell/2)*scale,(by+r*step+cell/2)*scale) for c,r in path]
    d.line(pts,fill=TEAL,width=12*scale,joint="curve")
    for x,y in pts:d.ellipse((x-6*scale,y-6*scale,x+6*scale,y+6*scale),fill=TEAL)
    for r in range(4):
        for c in range(4):
            x,y=(bx+c*step+cell/2)*scale,(by+r*step+cell/2)*scale
            if (c,r) in path:
                d.ellipse((x-23*scale,y-23*scale,x+23*scale,y+23*scale),fill=INK if (c,r) in [(0,0),(3,3)] else TEAL)
            center(d,(x,y),str(vals[r][c]),27*scale,CREAM if (c,r) in path else INK)
            if (c,r) in [(1,1),(3,2)]:
                kx,ky=x+25*scale,y-25*scale
                d.polygon([(kx,ky-9*scale),(kx+9*scale,ky),(kx,ky+9*scale),(kx-9*scale,ky)],fill=AMBER)
    d.rounded_rectangle((650*scale,420*scale,955*scale,466*scale),radius=23*scale,fill=INK)
    center(d,(750*scale,443*scale),"SUM 16 / 16" if language=="en" else "СУММА 16 / 16",17*scale,CREAM)
    d.polygon([(846*scale,435*scale),(854*scale,443*scale),(846*scale,451*scale),(838*scale,443*scale)],fill=AMBER)
    center(d,(901*scale,443*scale),"2 / 2",18*scale,CREAM)
    return im.resize((1024,500),Image.Resampling.LANCZOS)

def vector():
    active={(0,0),(1,0),(1,1),(2,1),(2,2),(3,2),(3,3)}
    paths=[]
    for r in range(4):
        for c in range(4):
            x,y=19+c*18,19+r*18
            paths.append(f'<path android:fillColor="{MINT if (c,r) in active else "#E5E6DF"}" android:pathData="M{x},{y}h16v16h-16z"/>')
    paths.append(f'<path android:strokeColor="{TEAL}" android:strokeWidth="4.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="@android:color/transparent" android:pathData="M27,27H45V45H63V63H81V81"/>')
    for c,r in [(0,0),(1,0),(2,1),(2,2),(3,3)]:
        x,y=27+c*18,27+r*18
        paths.append(f'<path android:fillColor="{INK if (c,r)==(3,3) else TEAL}" android:pathData="M{x-7},{y}a7,7 0,1 0,14 0a7,7 0,1 0,-14 0"/>')
    for p in ["M24,23h6v3l-6,3v2h6", "M42,23h6v8h-6M42,27h6", "M60,41v4h6M65,41v8", "M61,59l2,-1v9M60,67h6", "M84,77h-6v4h6v4h-6"]:
        paths.append(f'<path android:strokeColor="{CREAM}" android:strokeWidth="1.5" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="@android:color/transparent" android:pathData="{p}"/>')
    paths.append(f'<path android:fillColor="{AMBER}" android:pathData="M45,37.5L52.5,45L45,52.5L37.5,45z"/>')
    paths.append(f'<path android:fillColor="{INK}" android:pathData="M43,45a2,2 0,1 0,4 0a2,2 0,1 0,-4 0"/>')
    header='<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
    safe_group='<group android:pivotX="54" android:pivotY="54" android:scaleX="0.7" android:scaleY="0.7">\n'
    return header+safe_group+'\n'.join(paths)+'\n</group>\n</vector>\n', header+f'<path android:fillColor="{CREAM}" android:pathData="M0,0h108v108h-108z"/>\n'+'\n'.join(paths)+'\n</vector>\n'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    icon().convert('RGBA').save(OUT/'icon-512.png')
    for lang in ['ru','en']: feature(lang).save(OUT/f'feature-graphic-{lang}-1024x500.png')
    res=ROOT/'app/src/main/res'
    for folder in ['drawable','mipmap-anydpi','mipmap-anydpi-v26']: (res/folder).mkdir(parents=True,exist_ok=True)
    foreground,legacy=vector()
    (res/'drawable/ic_launcher_foreground.xml').write_text(foreground)
    (res/'drawable/ic_launcher_background.xml').write_text(f'<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="{CREAM}"/></shape>\n')
    (res/'mipmap-anydpi/ic_launcher.xml').write_text(legacy)
    (res/'mipmap-anydpi-v26/ic_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/></adaptive-icon>\n')
    web=ROOT/'docs/assets';web.mkdir(parents=True,exist_ok=True)
    icon(192).save(web/'icon.png')
    (OUT/'asset-manifest.json').write_text(json.dumps({'origin':'Original geometric artwork by jonkryl, generated from art/generate_assets.py; no third-party raster, level, font redistributions or branding','icon':{'file':'icon-512.png','size':[512,512],'mode':'RGBA','format':'32-bit PNG with alpha; opaque cream background; no rounded outer mask'},'adaptiveIcon':'Foreground scaled around center inside the Android safe area; separate full background layer','featureGraphics':[{'file':f'feature-graphic-{lang}-1024x500.png','language':lang,'size':[1024,500],'mode':'RGB','isDeviceScreenshot':False} for lang in ['ru','en']],'screenshots':'Only actual CI device captures may be submitted as device screenshots. These feature graphics are illustrative brand artwork.','requirementsSource':'https://support.google.com/googleplay/android-developer/answer/9866151?hl=en'},ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
