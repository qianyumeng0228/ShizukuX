import zipfile
z = zipfile.ZipFile('manager/build/outputs/apk/dropin/release/manager-dropin-release.apk')
dex_files = [n for n in z.namelist() if n.endswith('.dex')]
print(f'DEX files: {dex_files}')
for dex_name in dex_files:
    data = z.read(dex_name)
    for kw in ['spoofed', 'getUid called', 'checkPermission: callingUid', 'TRANSACT:']:
        idx = data.find(kw.encode('utf-8'))
        if idx != -1:
            print(f'  [{dex_name}] FOUND "{kw}" at offset {idx}')
        else:
            print(f'  [{dex_name}] NOT FOUND "{kw}"')
