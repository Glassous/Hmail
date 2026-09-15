"""Only HTTP connectivity checks. No accounts, messages or state are created."""
import json
import sys
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


base = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:5173'
results = []
for path, expected in [('/', 200), ('/api/v1/health', 200), ('/api/v1/config', 200), ('/api/openapi.json', 200), ('/api/docs', 200), ('/api/v1/me', 401), ('/api/v1/gmail-accounts', 401)]:
    try:
        with urlopen(base + path, timeout=15) as response:
            actual = response.status
            response.read()
    except HTTPError as exc:
        actual = exc.code
    except (URLError, TimeoutError):
        actual = 'unreachable'
    results.append({'path': path, 'expected': expected, 'actual': actual, 'passed': actual == expected})
print(json.dumps(results, ensure_ascii=False, indent=2))
sys.exit(0 if all(row['passed'] for row in results) else 1)
