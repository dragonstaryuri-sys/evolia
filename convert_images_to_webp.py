"""
图片 → WebP 转码工具（修复版）
修复之前 Kotlin 端 "两次压缩" bug 的 Python 等价实现：
  Bug 根因: 原图 → convertToJpeg(q=80) → 存中间文件 → 再次读取 → 转 WebP
            两次有损编码叠加导致画质劣化。
  修复策略: 只从源文件读取一次 → (可选 resize) → 直接 save('WEBP')，
            全程仅一次编码，无中间格式落盘。

核心规则:
  1. 非 WebP 源 → 转 WebP（仅一次编码）
  2. 长边 > MAX_SIDE → 先等比缩放，再写 WebP
  3. 已是 WebP → 不重编码；仅当超阈值时 resize 后写回
  4. 目标体积 ≤ TARGET_SIZE_BYTES，quality 自动降级兜底
"""

import os
import shutil
from io import BytesIO
from PIL import Image

# ============ 可调参数 ============
MAX_SIDE = 1920                       # 长边阈值（px），超过则等比缩放
TARGET_SIZE_BYTES = 500 * 1024        # 单张目标 ≤ 500KB（项目硬约束）
WEBP_QUALITY_BASE = 82                # 基础 quality，超出目标体积时逐步降级
QUALITY_MIN = 50                      # quality 下限，不继续降了就放弃压缩精度
WEBP_METHOD = 6                       # 0-6，6=最慢但压缩率最高
SUPPORTED_EXTS = {'.jpg', '.jpeg', '.png', '.gif', '.bmp',
                  '.tiff', '.tif', '.webp'}
# ==================================


def _resize_if_needed(img: Image.Image) -> Image.Image:
    w, h = img.size
    longer = max(w, h)
    if longer <= MAX_SIDE:
        return img
    ratio = MAX_SIDE / longer
    new_w = max(1, round(w * ratio))
    new_h = max(1, round(h * ratio))
    return img.resize((new_w, new_h), Image.LANCZOS)


def _normalize_mode(img: Image.Image) -> Image.Image:
    """GIF 多帧取首帧；带透明通道的保留 RGBA；其余转 RGB（WebP 都支持）。"""
    if img.format == 'GIF' and getattr(img, 'is_animated', False):
        img.seek(0)
    if img.mode in ('RGBA', 'LA'):
        return img.convert('RGBA')
    if img.mode == 'P' and 'transparency' in img.info:
        return img.convert('RGBA')
    return img.convert('RGB')


def _try_save_webp(img: Image.Image, dst_path: str) -> int:
    """
    用 WEBP_QUALITY_BASE 尝试写入；若超目标体积，逐步降 quality。
    返回最终使用的 quality。全程只编码一次到 BytesIO，通过内存
    判断体积后再落盘，避免反复写临时文件。
    """
    quality = WEBP_QUALITY_BASE
    buf = BytesIO()

    while True:
        buf.seek(0)
        buf.truncate()
        img.save(buf, 'WEBP', quality=quality, method=WEBP_METHOD)
        size = buf.tell()
        if size <= TARGET_SIZE_BYTES or quality <= QUALITY_MIN:
            break
        quality -= 10

    with open(dst_path, 'wb') as f:
        f.write(buf.getvalue())
    return quality


def convert_one(src_path: str, out_dir: str):
    """
    处理单张图片。返回 (status, dst_path, info_str)。
    status: 'converted' | 'resized_only' | 'skipped' | 'error'
    """
    ext = os.path.splitext(src_path)[1].lower()
    base = os.path.splitext(os.path.basename(src_path))[0]
    dst_webp = os.path.join(out_dir, base + '.webp')

    os.makedirs(out_dir, exist_ok=True)

    if ext == '.webp':
        # 已是 WebP —— 不做重编码，只按需 resize 再写；不 resize 就直接 copy
        with Image.open(src_path) as img:
            w, h = img.size
            if max(w, h) <= MAX_SIDE:
                if os.path.abspath(src_path) != os.path.abspath(dst_webp):
                    shutil.copy2(src_path, dst_webp)
                return ('skipped', dst_webp, f'already webp {w}x{h}')
            img = _resize_if_needed(img)
            img.save(dst_webp, 'WEBP', quality=WEBP_QUALITY_BASE, method=WEBP_METHOD)
            fsize = os.path.getsize(dst_webp)
            return ('resized_only', dst_webp, f'webp+resize -> {img.size}, {fsize}B')

    # 非 WebP：只读源文件一次，normalize → resize → 一次编码写 WebP
    with Image.open(src_path) as img:
        orig_size = img.size
        img = _normalize_mode(img)
        img = _resize_if_needed(img)
        used_q = _try_save_webp(img, dst_webp)

    fsize = os.path.getsize(dst_webp)
    new_size = Image.open(dst_webp).size
    return (
        'converted',
        dst_webp,
        f'{orig_size}->{new_size}, q={used_q}, {fsize}B',
    )


def batch_convert(input_dir: str, output_dir: str = None):
    output_dir = output_dir or input_dir
    os.makedirs(output_dir, exist_ok=True)

    print(f"Input : {input_dir}")
    print(f"Output: {output_dir}")
    print(f"Config: max_side={MAX_SIDE}px  target<={TARGET_SIZE_BYTES}B  "
          f"q_base={WEBP_QUALITY_BASE}  q_min={QUALITY_MIN}")
    print("-" * 60)

    ok = skip = err = 0
    for fname in sorted(os.listdir(input_dir)):
        fpath = os.path.join(input_dir, fname)
        if not os.path.isfile(fpath):
            continue
        if os.path.splitext(fname)[1].lower() not in SUPPORTED_EXTS:
            continue
        try:
            status, dst, info = convert_one(fpath, output_dir)
            tag = {'converted': 'OK', 'resized_only': 'RESZ',
                   'skipped': 'SKIP', 'error': 'ERR'}[status]
            print(f"[{tag:>4s}] {fname:40s} -> {os.path.basename(dst):40s}  {info}")
            if status == 'converted':
                ok += 1
            elif status == 'skipped':
                skip += 1
        except Exception as e:
            print(f"[ ERR ] {fname:40s}  {type(e).__name__}: {e}")
            err += 1

    print("-" * 60)
    print(f"Done. converted={ok}  skipped={skip}  errors={err}")
    return ok, skip, err


# ============ 当脚本直接运行时 ============
if __name__ == '__main__':
    # 优先使用 WORKING_DIR（Chaquopy executor 会注入），否则用脚本所在目录
    WD = os.environ.get('WORKING_DIR', os.path.dirname(os.path.abspath(__file__)))

    # 若存在 images/ 子目录就扫它，否则扫 WD 下所有支持格式文件
    candidate = os.path.join(WD, 'images')
    input_dir = candidate if os.path.isdir(candidate) else WD
    output_dir = os.path.join(WD, 'webp_out')

    batch_convert(input_dir, output_dir)
