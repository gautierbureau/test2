import pathlib
import sys

# Make the python/ directory importable so tests can do:  from transport_curve import ...
sys.path.insert(0, str(pathlib.Path(__file__).parent.parent))
