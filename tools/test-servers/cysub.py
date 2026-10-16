import sys, json, base64, urllib.request, os
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab
u=lab.user_for({"name":sys.argv[2]}); u=u if isinstance(u,str) else u[0]
H={"Authorization":"Basic "+base64.b64encode(f"{u}:{lab.PASS}".encode()).decode(),"Content-Type":"application/json"}
base=f"http://{lab.LAB_HOST}:{sys.argv[1]}"
def get(url,data=None):
    return json.load(urllib.request.urlopen(urllib.request.Request(url,data=data,headers=H)))
s=get(base+"/jmap/"); api=s["apiUrl"]; api=api if api.startswith("http") else base+api
acc=s["primaryAccounts"]["urn:ietf:params:jmap:mail"]
print("caps", [c for c in s["capabilities"] if "submission" in c], s["capabilities"].get("urn:ietf:params:jmap:submission"))
U=["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail","urn:ietf:params:jmap:submission"]
def call(m): return get(api,json.dumps({"using":U,"methodCalls":m}).encode())["methodResponses"]
print("identities", json.dumps(call([["Identity/get",{"accountId":acc},"0"]]))[:400])
mb=call([["Mailbox/get",{"accountId":acc,"properties":["name","parentId"]},"0"]])[0][1]["list"]
sent=[m["id"] for m in mb if m["name"]=="Sent" and not m.get("parentId")][0]
rc=s["username"] if "@" in s["username"] else s["username"]
r=call([["Email/set",{"accountId":acc,"create":{"e":{"mailboxIds":{sent:True},"from":[{"email":rc}],"to":[{"email":rc}],"subject":"cysub","bodyValues":{"b":{"value":"x"}},"textBody":[{"partId":"b","type":"text/plain"}]}}},"0"],
        ["EmailSubmission/set",{"accountId":acc,"create":{"k1":{"emailId":"#e","envelope":{"mailFrom":{"email":rc},"rcptTo":[{"email":rc}]}}}},"1"]])
print(json.dumps(r)[:900])
