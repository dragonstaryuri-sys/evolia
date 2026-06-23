"""
Python executor for LastChat Python Workbench.
Provides safe code execution with stdout capture and error handling.
"""

import sys
import os
import json
from io import StringIO

def is_safe_path(filepath: str, working_dir: str) -> bool:
    """Check if the filepath is safely within the working_dir."""
    try:
        working_dir = os.path.realpath(working_dir)
        # If filepath doesn't exist yet, realpath might not fully resolve it if it's a new file,
        # but it will resolve the parent parts that do exist.
        abs_path = os.path.abspath(os.path.join(working_dir, filepath))
        real_path = os.path.realpath(abs_path)
        return os.path.commonpath([real_path, working_dir]) == working_dir
    except Exception:
        return False

def execute(code: str, working_dir: str) -> str:
    """
    Execute Python code with stdout/stderr capture.
    """
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    try:
        working_dir = os.path.realpath(working_dir)
        os.makedirs(working_dir, exist_ok=True)
        os.chdir(working_dir)

        # Matplotlib config
        os.environ['MPLCONFIGDIR'] = os.path.join(working_dir, ".matplotlib")
        os.makedirs(os.environ['MPLCONFIGDIR'], exist_ok=True)

        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        import matplotlib.font_manager as fm

        # Font setup
        current_script_dir = os.path.dirname(__file__)
        potential_fonts = []
        try:
            for root, dirs, files in os.walk(current_script_dir):
                for f in files:
                    if f.lower().endswith(('.ttf', '.ttc', '.otf')):
                        potential_fonts.append(os.path.join(root, f))
        except: pass
        potential_fonts.extend([
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/product/fonts/NotoSansCJK-Regular.ttc",
            "/product/fonts/NotoSansCJK-Regular.ttc"
        ])
        for font_path in potential_fonts:
            if os.path.exists(font_path):
                try:
                    fm.fontManager.addfont(font_path)
                    prop = fm.FontProperties(fname=font_path)
                    plt.rcParams['font.sans-serif'] = [prop.get_name(), 'sans-serif']
                    plt.rcParams['axes.unicode_minus'] = False
                    break
                except: continue

        plt.rcParams['figure.facecolor'] = 'white'
        plt.rcParams['axes.facecolor'] = 'white'
        plt.rcParams['savefig.facecolor'] = 'white'

        result = None
        error = None
        exec_globals = {
            '__name__': '__main__',
            '__builtins__': __builtins__,
            'plt': plt,
            'matplotlib': matplotlib,
            'WORKING_DIR': working_dir,
            'os': os,
            'sys': sys
        }

        try:
            # Try eval first
            result = eval(code, exec_globals)
        except SyntaxError:
            try:
                exec(code, exec_globals)
                # Auto-save figures
                figures = plt.get_fignums()
                for i, fig_num in enumerate(figures):
                    fig = plt.figure(fig_num)
                    filename = f"figure_{i + 1}.png" if len(figures) > 1 else "figure.png"
                    fig.savefig(filename, dpi=150, bbox_inches='tight')
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
        try:
            import matplotlib.pyplot as plt
            plt.close('all')
        except: pass

    response = {}
    if error: response["error"] = error
    elif result is not None: response["result"] = str(result)
    if stdout_output: response["stdout"] = stdout_output
    if stderr_output: response["stderr"] = stderr_output
    if not response: response["result"] = "Executed successfully"
    return json.dumps(response)

def read_file(filepath: str, working_dir: str) -> str:
    if not is_safe_path(filepath, working_dir):
        return json.dumps({"error": "Access denied"})
    try:
        full_path = os.path.abspath(os.path.join(working_dir, filepath))
        with open(full_path, 'r', encoding='utf-8') as f:
            return json.dumps({"content": f.read()})
    except Exception as e:
        return json.dumps({"error": str(e)})

def write_file(filepath: str, content: str, working_dir: str) -> str:
    if not is_safe_path(filepath, working_dir):
        return json.dumps({"error": "Access denied"})
    try:
        full_path = os.path.abspath(os.path.join(working_dir, filepath))
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, 'w', encoding='utf-8') as f:
            f.write(content)
        return json.dumps({"path": os.path.relpath(full_path, working_dir), "success": True})
    except Exception as e:
        return json.dumps({"error": str(e)})

def list_files(working_dir: str) -> str:
    try:
        working_dir = os.path.realpath(working_dir)
        files = []
        for root, _, filenames in os.walk(working_dir):
            for filename in filenames:
                full_path = os.path.join(root, filename)
                files.append({
                    "name": os.path.relpath(full_path, working_dir),
                    "size": os.path.getsize(full_path),
                    "mtime": os.path.getmtime(full_path)
                })
        return json.dumps({"files": files})
    except Exception as e:
        return json.dumps({"error": str(e)})
