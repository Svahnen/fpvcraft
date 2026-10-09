#!/usr/bin/env python3
"""Cut the recorded demo takes into the final demo video (ffmpeg, drawtext captions, original music bed).

usage: assemble.py <takes_dir> <work_dir> <out_dir>
  takes_dir: <take>.mp4 / <take>.csv from the demo recorder; <take>.wav is rendered here with render_audio.py.
  out: demo.mp4, demo-preview.gif, stills.
Fonts: Montserrat (SIL Open Font License 1.1). Music: tools/demo/make_music.py (CC0). Sounds: the mod's own synthesised CC0 layers.
"""
import os, subprocess, sys, json

FF = '/usr/bin/ffmpeg'   # needs libfreetype (drawtext) and libx264
FONT_B = '/usr/share/fonts/julietaula-montserrat-fonts/Montserrat-Bold.otf'
FONT_X = '/usr/share/fonts/julietaula-montserrat-fonts/Montserrat-ExtraBold.otf'
W, H, FPS = 1920, 1080, 60

# (take, in, out, [captions (text, from, to) relative to the segment start])
EDL = [
    ('chase', 0.0, 4.0, [('@title', 0.3, 3.7)]),
    ('chase', 4.0, 8.0, [('Real acro flight physics', 0.4, 3.6)]),
    ('var', 8.0, 11.0, []),
    ('osd', 11.0, 15.0, [('Betaflight-style rates, battery and OSD', 0.4, 3.6)]),
    ('fpv', 15.0, 19.0, []),
    ('chase', 16.5, 20.5, [('Low over water, through the waterfall', 0.4, 3.6)]),
    ('cal', 1.5, 8.5, [('Use your real RC radio', 0.4, 6.6, 'b')]),
    ('wing', 1.0, 8.0, [('Multiplayer: others see your drone', 0.4, 3.4), ('and the body you left behind', 3.6, 6.6)]),
    ('f5', 5.5, 12.5, [('F5 camera modes', 0.4, 6.6)]),
    ('chase', 22.5, 26.5, [('Dive, corkscrew, power loop', 0.4, 3.6)]),
    ('fpv', 24.0, 27.5, []),
    ('chase', 29.0, 33.0, []),
    ('var', 29.0, 35.0, [('Chunks load around the drone', 0.4, 5.6)]),
    ('fpv', 39.0, 43.0, []),
    ('var', 38.5, 42.5, []),
    ('chase', 44.5, 47.5, []),
    ('fw', 46.6, 50.0, []),
    ('chase', 48.5, 52.0, [('@end', 0.3, 3.5)]),
]


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print(' '.join(cmd)); print(r.stderr[-2000:]); sys.exit(1)


def esc(t): return t.replace('\\', '\\\\').replace(':', '\\:').replace("'", '').replace(',', '\\,').replace('%', '\\%')


def dt(text, a, b, size, font, y, box=True, x='90', color='white'):
    alpha = f"if(lt(t\\,{a}+0.25)\\,(t-{a})/0.25\\,if(gt(t\\,{b}-0.25)\\,({b}-t)/0.25\\,1))"
    s = (f"drawtext=fontfile={font}:text='{esc(text)}':fontsize={size}:fontcolor={color}:x={x}:y={y}:"
         f"shadowcolor=black@0.7:shadowx=3:shadowy=3:alpha='{alpha}':enable='between(t\\,{a}\\,{b})'")
    if box: s += ":box=1:boxcolor=black@0.42:boxborderw=24"
    return s


def main():
    takes, work, out = sys.argv[1:4]
    os.makedirs(work, exist_ok=True); os.makedirs(out, exist_ok=True)
    here = os.path.dirname(os.path.abspath(__file__))
    for t in {e[0] for e in EDL}:
        wav = f'{work}/{t}.wav'
        if not os.path.exists(wav):
            run([sys.executable, here + '/render_audio.py', f'{takes}/{t}.csv', wav])
    segs = []
    for i, (take, a, b, caps) in enumerate(EDL):
        d = b - a
        vf = [f'scale={W}:{H}:flags=lanczos', f'fps={FPS}']
        for cap in caps:
            text, ca, cb = cap[:3]
            if text == '@title':
                vf.append(f"drawbox=x=0:y=0:w=iw:h=ih:color=black@0.28:t=fill:enable='between(t\\,0\\,{cb})'")
                vf.append(dt('FPVcraft', ca, cb, 118, FONT_X, '(h-th)/2-50', box=False, x='(w-tw)/2'))
                vf.append(dt('A realistic FPV racing drone for Minecraft', ca + 0.3, cb, 44, FONT_B, '(h)/2+70', box=False, x='(w-tw)/2'))
            elif text == '@end':
                vf.append(f"drawbox=x=0:y=0:w=iw:h=ih:color=black@0.45:t=fill:enable='between(t\\,{ca}\\,{d})'")
                vf.append(dt('FPVcraft', ca, d, 104, FONT_X, '(h-th)/2-80', box=False, x='(w-tw)/2'))
                vf.append(dt('Free and open source', ca + 0.3, d, 52, FONT_B, '(h)/2+20', box=False, x='(w-tw)/2'))
                vf.append(dt('Fabric 1.21.1  -  real physics  -  real RC radios  -  multiplayer', ca + 0.6, d, 34, FONT_B, '(h)/2+100', box=False, x='(w-tw)/2'))
            else:
                vf.append(dt(text, ca, cb, 54, FONT_B, 'h-150' if len(cap) > 3 else '110'))
        if i == 0: vf.append('fade=t=in:st=0:d=0.5')
        if i == len(EDL) - 1: vf.append(f'fade=t=out:st={d - 0.7}:d=0.7')
        f = f'{work}/seg{i:02d}.mp4'
        af = f'afade=t=in:d=0.03,afade=t=out:st={d - 0.03}:d=0.03'
        run([FF, '-y', '-v', 'error', '-ss', str(a), '-t', str(d), '-i', f'{takes}/{take}.mp4', '-ss', str(a), '-t', str(d), '-i', f'{work}/{take}.wav',
             '-map', '0:v', '-map', '1:a', '-vf', ','.join(vf), '-af', af, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '12', '-pix_fmt', 'yuv420p',
             '-c:a', 'pcm_s16le', '-ar', '44100', f])
        segs.append(f)
    lst = f'{work}/list.txt'
    open(lst, 'w').write(''.join(f"file '{s}'\n" for s in segs))
    total = sum(b - a for _, a, b, _ in EDL)
    music = f'{work}/music.wav'
    if not os.path.exists(music):
        run([sys.executable, here + '/make_music.py', music, str(total + 1)])
    master = f'{work}/master.mkv'
    run([FF, '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', lst, '-i', music,
         '-filter_complex', f'[0:a]volume=3.2[m];[1:a]volume=0.55[mu];[m][mu]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.9,loudnorm=I=-16:TP=-1.5:LRA=11[a]',
         '-map', '0:v', '-map', '[a]', '-c:v', 'copy', '-c:a', 'pcm_s16le', master])
    print('master', total, 's')
    # final: H.264 high, faststart, size limit 25 MB
    vb = os.environ.get('VBITRATE') or f'{int((22.8e6 * 8 / total - 128e3) / 1000)}k'
    run([FF, '-y', '-v', 'error', '-i', master, '-c:v', 'libx264', '-preset', 'slow', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-b:v', vb, '-maxrate', '3200k', '-bufsize', '6400k',
         '-c:a', 'aac', '-b:a', '128k', '-movflags', '+faststart', f'{out}/demo.mp4'])
    print('demo.mp4', os.path.getsize(f'{out}/demo.mp4') / 1e6, 'MB')


# highlight pieces of the master (start, length in seconds) for the README preview
GIF_PIECES = [(1, 0.0, 4.0), (5, 0.0, 4.0), (9, 0.0, 4.0)]   # (EDL index, offset in the segment, length)
# stills: (take, time in the take, file name)
STILLS = [('chase', 5.4, 'gates.png'), ('osd', 13.0, 'fpv-osd-tunnel.png'), ('wing', 4.3, 'multiplayer.png'), ('cal', 7.0, 'rc-calibration.png'), ('fw', 47.6, 'finish-fireworks.png')]


def preview(work, out):
    master = f'{work}/master.mkv'
    starts = [0.0]
    for _, a, b, _ in EDL: starts.append(starts[-1] + b - a)
    pieces = [(starts[i] + o, d) for i, o, d in GIF_PIECES]
    best = None
    for fps, width, colors in ((15, 720, 128), (12, 720, 96), (10, 720, 64), (8, 720, 48), (10, 640, 64), (8, 640, 48)):
        parts = ''.join(f'[0:v]trim=start={a}:duration={d},setpts=PTS-STARTPTS,fps={fps},scale={width}:-2:flags=lanczos[p{i}];' for i, (a, d) in enumerate(pieces))
        cat = ''.join(f'[p{i}]' for i in range(len(pieces))) + f'concat=n={len(pieces)}:v=1:a=0[v]'
        run([FF, '-y', '-v', 'error', '-i', master, '-filter_complex', parts + cat + f';[v]split[a][b];[a]palettegen=max_colors={colors}:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle[o]',
             '-map', '[o]', '-loop', '0', f'{out}/demo-preview.gif'])
        size = os.path.getsize(f'{out}/demo-preview.gif') / 1e6
        print('gif', fps, width, colors, size, 'MB')
        if size <= 7.6: break
    # animated WebP (much smaller, 720 px, 15 fps)
    parts = ''.join(f'[0:v]trim=start={a}:duration={d},setpts=PTS-STARTPTS,fps=15,scale=720:-2:flags=lanczos[p{i}];' for i, (a, d) in enumerate(pieces))
    cat = ''.join(f'[p{i}]' for i in range(len(pieces))) + f'concat=n={len(pieces)}:v=1:a=0[v]'
    run([FF, '-y', '-v', 'error', '-i', master, '-filter_complex', parts + cat, '-map', '[v]', '-c:v', 'libwebp_anim', '-quality', '62', '-loop', '0', f'{out}/demo-preview.webp'])
    print('webp', os.path.getsize(f'{out}/demo-preview.webp') / 1e6, 'MB')


def stills(takes, work, out):
    from PIL import Image
    for take, t, name in STILLS:
        tmp = f'{work}/still_{name}'
        run([FF, '-y', '-v', 'error', '-ss', str(t), '-i', f'{takes}/{take}.mp4', '-frames:v', '1', tmp])
        im = Image.open(tmp).convert('RGB')
        im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(f'{out}/{name}', optimize=True)
        print(name, os.path.getsize(f'{out}/{name}') / 1e6, 'MB')


if __name__ == '__main__':
    main()
    preview(sys.argv[2], sys.argv[3])
    stills(sys.argv[1], sys.argv[2], sys.argv[3])
