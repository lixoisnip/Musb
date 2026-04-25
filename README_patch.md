# Suggested README update

## Firmware images

The repository currently contains more firmware images than the original four-entry README list. Treat all `*.PZU` files as read-only source artifacts for reverse engineering.

Known corpus:

- `A03_26.PZU`
- `A04_28.PZU`
- `ppkp2012 a01.PZU`
- `ppkp2019 a02.PZU`
- `ppkp2001 90cye01.PZU`
- `90CYE02_27 DKS.PZU`
- `90CYE03_19_DKS.PZU`
- `90CYE04_19_DKS.PZU`
- `90CYE03_19_2 v2_1.PZU`
- `90CYE04_19_2 v2_1.PZU`

Use:

```bash
python scripts/firmware_manifest.py .
python scripts/family_matrix.py *.PZU
python scripts/xdata_xref.py *.PZU
```

Generated files:

- `docs/firmware_manifest.json`
- `docs/firmware_inventory.csv`
- `docs/firmware_family_matrix.csv`
- `docs/xdata_xref.csv`

Known issue: older repository analysis reports checksum problems in `ppkp2012 a01.PZU` and `ppkp2019 a02.PZU`. Do not silently repair or normalize these files. Keep the original artifacts and record validation status in the generated manifest.
