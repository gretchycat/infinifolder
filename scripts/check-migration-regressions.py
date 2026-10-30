#!/usr/bin/env python3
"""Compare existing JUnit cases to the recorded pre-migration baseline.

Supply current test-result directories explicitly. No missing baseline case or new
failure is allowed. Existing known failures remain visible and do not count as a
green full suite. This compares results; it does not run or skip any tests.
"""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', type=Path, default=Path('docs/validation/infinistream-baseline.json'))
parser.add_argument('results', nargs='+', type=Path)
args = parser.parse_args()
baseline = json.loads(args.baseline.read_text())['cases']
observed = {}
for directory in args.results:
    files = list(directory.glob('TEST-*.xml'))
    if not files:
        parser.error(f'No JUnit XML files: {directory}')
    for file in files:
        root = ET.parse(file).getroot()
        for case in root.findall('testcase'):
            key = case.attrib['classname'] + '.' + case.attrib['name']
            status = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            # Duplicate reports must not hide a failing/skipped case.
            prior = observed.get(key)
            if prior != 'failed' and (status == 'failed' or prior != 'skipped'):
                observed[key] = status
missing = sorted(set(baseline) - observed.keys())
regressed = sorted(key for key, state in observed.items() if state != 'passed' and baseline.get(key) != state)
known = sorted(key for key, state in observed.items() if state == 'failed' and baseline.get(key) == state)
fixed = sorted(key for key, state in baseline.items() if state == 'failed' and observed.get(key) == 'passed')
print(f'{len(observed)} reported cases; {len(known)} known baseline failures; {len(fixed)} resolved; {len(regressed)} regressions; {len(missing)} missing baseline cases')
for label, cases in [('REGRESSION', regressed), ('MISSING', missing), ('RESOLVED', fixed), ('KNOWN BASELINE FAILURE', known)]:
    for case in cases:
        print(f'{label}: {case}')
sys.exit(bool(missing or regressed))
