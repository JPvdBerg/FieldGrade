#!/usr/bin/env python3
import json, sys, time

def main():
    print("Controller simulator ready; send one JSON command per line.")
    last=time.monotonic()
    for line in sys.stdin:
        try:
            cmd=json.loads(line)
            output=0 if not cmd.get("enable") else max(-1000,min(1000,cmd.get("manual",0) if cmd.get("mode")=="MANUAL" else cmd.get("target_mm",0)*12))
            print(json.dumps({"v":1,"seq_ack":cmd.get("seq",0),"state":"ACTIVE" if output else "NEUTRAL","output":output,"estop":False,"fault":0,"age_ms":0}), flush=True)
            last=time.monotonic()
        except Exception as exc:
            print(json.dumps({"state":"FAULT","fault":3,"message":str(exc)}), flush=True)
if __name__ == "__main__": main()
