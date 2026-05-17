"""
Python executor for LastChat Python Workbench.
Provides safe code execution with stdout capture and error handling.
"""

import sys
import os
import json
from io import StringIO


def execute(code: str, working_dir: str) -> str:
    """
    Execute Python code with stdout/stderr capture.

    Args:
        code: Python code to execute
        working_dir: Working directory for file operations

    Returns:
        JSON string with result/stdout/error
    """
    # 1. 立即捕获输出，确保所有调试信息都能返回给 AI 和用户
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    try:
        os.chdir(working_dir)

        # 显式设置 Matplotlib 配置目录到沙盒可写目录，防止字体缓存写入失败
        os.environ['MPLCONFIGDIR'] = os.path.join(working_dir, ".matplotlib")
        os.makedirs(os.environ['MPLCONFIGDIR'], exist_ok=True)

        # 2. Configure matplotlib for non-GUI environment
        import matplotlib
        matplotlib.use('Agg')  # 必须在导入 pyplot 之前设置
        import matplotlib.pyplot as plt
        import matplotlib.font_manager as fm

        # --- 自动检测并“强制注册” 中文字体 ---
        # 优先级 1: 开发者内置在 python 源码目录及其子目录（如 font/）下的字体 (推荐)
        # 优先级 2: 系统预设路径
        # 优先级 3: 暴力扫描系统目录

        current_script_dir = os.path.dirname(__file__)
        potential_fonts = []

        # 自动搜索源码目录及其子文件夹（如 font/）下的所有 ttf/ttc/otf
        try:
            for root, dirs, files in os.walk(current_script_dir):
                for f in files:
                    if f.lower().endswith(('.ttf', '.ttc', '.otf')):
                        potential_fonts.append(os.path.join(root, f))
        except: pass

        # 加上常见的系统路径
        potential_fonts.extend([
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/product/fonts/NotoSansCJK-Regular.ttc",
            "/product/fonts/NotoSansCJK-Regular.ttc"
        ])

        font_loaded = False
        for font_path in potential_fonts:
            if os.path.exists(font_path):
                try:
                    # 核心：直接将字体文件注册到 Matplotlib 的管理器中
                    fm.fontManager.addfont(font_path)
                    prop = fm.FontProperties(fname=font_path)
                    actual_font_name = prop.get_name()

                    # 设置为全局默认字体
                    plt.rcParams['font.sans-serif'] = [actual_font_name, 'sans-serif']
                    plt.rcParams['axes.unicode_minus'] = False  # 解决负号方块

                    print(f"DEBUG: Successfully loaded Chinese font: {actual_font_name} from {font_path}")
                    font_loaded = True
                    break
                except Exception as e:
                    print(f"DEBUG: Failed to register {font_path}: {e}")
                    continue

        if not font_loaded:
            print("DEBUG: WARNING: No Chinese fonts found in source dir or system. Plots may show squares.")
        # ---------------------------------------

        plt.rcParams['figure.facecolor'] = 'white'
        plt.rcParams['axes.facecolor'] = 'white'
        plt.rcParams['savefig.facecolor'] = 'white'

        result = None
        error = None

        # Pre-populate globals
        exec_globals = {
            '__name__': '__main__',
            '__builtins__': __builtins__,
            'plt': plt,
            'matplotlib': matplotlib,
        }

        try:
            # Try to evaluate as expression first (returns value)
            result = eval(code, exec_globals)
        except SyntaxError:
            # Not an expression, execute as statements
            try:
                exec(code, exec_globals)
                # Auto-save any open matplotlib figures
                figures = plt.get_fignums()
                for i, fig_num in enumerate(figures):
                    fig = plt.figure(fig_num)
                    filename = f"figure_{i + 1}.png" if len(figures) > 1 else "figure.png"
                    fig.savefig(filename, dpi=150, bbox_inches='tight', facecolor='white', edgecolor='none')
                    plt.close(fig)
            except Exception as e:
                error = f"{type(e).__name__}: {str(e)}"
        except Exception as e:
            error = f"{type(e).__name__}: {str(e)}"
    except Exception as e:
        error = f"Setup Error: {str(e)}"
    finally:
        stdout_output = sys.stdout.getvalue()
        stderr_output = sys.stderr.getvalue()
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        # Clean up
        try:
            import matplotlib.pyplot as plt
            plt.close('all')
        except: pass

    response = {}
    if error:
        response["error"] = error
    elif result is not None:
        response["result"] = str(result)

    if stdout_output:
        response["stdout"] = stdout_output
    if stderr_output:
        response["stderr"] = stderr_output

    if not response:
        response["result"] = "Executed successfully"

    return json.dumps(response)


def read_file(filepath: str, working_dir: str) -> str:
    """Read file content safely from sandbox."""
    try:
        if not os.path.isabs(filepath):
            filepath = os.path.join(working_dir, filepath)
        real_path = os.path.realpath(filepath)
        if not real_path.startswith(os.path.realpath(working_dir)):
            return json.dumps({"error": "Access denied"})
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
        return json.dumps({"content": content})
    except Exception as e:
        return json.dumps({"error": str(e)})


def write_file(filepath: str, content: str, working_dir: str) -> str:
    """Write content safely to sandbox."""
    try:
        if not os.path.isabs(filepath):
            filepath = os.path.join(working_dir, filepath)
        if not os.path.realpath(filepath).startswith(os.path.realpath(working_dir)):
            return json.dumps({"error": "Access denied"})
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        return json.dumps({"path": filepath, "success": True})
    except Exception as e:
        return json.dumps({"error": str(e)})


def list_files(working_dir: str) -> str:
    """List files in sandbox."""
    try:
        files = []
        for root, _, filenames in os.walk(working_dir):
            for filename in filenames:
                full_path = os.path.join(root, filename)
                files.append({"name": os.path.relpath(full_path, working_dir), "size": os.path.getsize(full_path)})
        return json.dumps({"files": files})
    except Exception as e:
        return json.dumps({"error": str(e)})
