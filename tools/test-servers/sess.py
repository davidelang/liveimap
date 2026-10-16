import os,sys,json,ssl,base64,urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab
u=lab.user_for({"name":"stalwart"}); u=u if isinstance(u,str) else u[0]
tok=base64.b64encode(f"{u}:{lab.PASS}".encode()).decode()
r=urllib.request.urlopen(urllib.request.Request(f"https://{lab.LAB_HOST}:{sys.argv[1]}/jmap/session",headers={"Authorization":"Basic "+tok}),context=ssl._create_unverified_context())
j=json.load(r); print(j["apiUrl"], j.get("primaryAccounts"), list(j.get("accounts",{}))[:3])
