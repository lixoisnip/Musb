# Codex task: expand PZU reverse engineering pipeline

Repository: `lixoisnip/Ppkp`.

Goal: turn the current one-off PZU analysis into a reproducible pipeline that covers every `*.PZU` firmware image in the repository.

## Required changes

1. Add reusable Intel HEX parser helpers in `scripts/pzu_common.py`.
2. Add `scripts/validate_pzu.py` to validate checksum, EOF, record count, address range, vectors, and string previews.
3. Add `scripts/firmware_manifest.py` to generate:
   - `docs/firmware_manifest.json`
   - `docs/firmware_inventory.csv`
4. Add `scripts/family_matrix.py` to classify firmware families using start vectors and embedded labels.
5. Add `scripts/xdata_xref.py` to statically scan 8051 code for `MOV DPTR,#imm16` and classify nearby `MOVX`, `MOVC`, `LCALL`, and related patterns.
6. Add GitHub Actions workflow `.github/workflows/pzu-analysis.yml` that runs the three analysis scripts and uploads generated artifacts.
7. Update `README.md` so it lists the full known `*.PZU` corpus, not only the original four images.
8. Do not edit the `*.PZU` binaries/text images. They are source artifacts.

## Acceptance criteria

- `python scripts/firmware_manifest.py .` runs from repo root.
- `python scripts/family_matrix.py *.PZU` runs from repo root.
- `python scripts/xdata_xref.py *.PZU` runs from repo root.
- The generated manifest records checksum failures rather than hiding them.
- The README warns that `.PZU` files are firmware images in Intel HEX format and must not be hand-edited.
- No firmware image is rewritten, normalized, reformatted, or patched.

## Important technical notes

- Expected code-space range is normally `0x4000..0xBFFF`.
- Expected data record shape is usually `512` records of `0x40` bytes plus EOF.
- Known historical checksum failures: `ppkp2012 a01.PZU` and `ppkp2019 a02.PZU`, line 512 in existing analysis.
- Previously confirmed A03/A04 vectors include `0x4100`, `0x4176`, `0x41D0`, `0x492E`, `0x4954`, `0x497A`.
- A shifted 90CYE branch may use `0x4933`, `0x4959`, `0x497F`.
- RTOS/service branch images may jump into `0xBxxx` and contain labels such as `Ver.2013s/ RTOS`.
