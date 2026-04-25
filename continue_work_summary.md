# Continued analysis summary

The uploaded technical report correctly identifies the next work package: move from manual inspection to reproducible tooling.

Implemented in this bundle:

- strict Intel HEX parser with checksum and EOF validation;
- firmware manifest generator;
- family matrix generator based on vectors and embedded labels;
- 8051 DPTR/XDATA cross-reference scanner;
- GitHub Actions workflow draft;
- README patch text;
- Codex task for applying the changes to the repository.

The DPTR/XDATA scanner is intentionally conservative. It does not claim full disassembly. It identifies likely XDATA reads/writes through local instruction patterns around `MOV DPTR,#imm16`.

Recommended next step after running the scripts in the repo:

1. Inspect `docs/xdata_xref.csv`.
2. Group repeated `dptr_target` values by firmware family.
3. Promote repeated addresses into a new `docs/xdata_map_by_branch.csv`.
4. Compare A03/A04 known addresses against 90CYE and RTOS branches.
5. Only after this, start naming functions and packet builders.
