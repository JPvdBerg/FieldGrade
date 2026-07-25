from pathlib import Path
import json, subprocess, sys

def test_simulator_manual_command():
    p = subprocess.Popen([sys.executable, str(Path(__file__).parents[1]/"tools"/"controller_simulator.py")], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    p.stdout.readline()
    p.stdin.write(json.dumps({"seq":1,"mode":"MANUAL","manual":250,"target_mm":0,"enable":True})+"\n")
    p.stdin.flush()
    status=json.loads(p.stdout.readline())
    assert status["output"] == 250
    p.terminate()
