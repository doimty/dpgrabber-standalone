"""Build provenance.

CI overwrites this file before packaging:

    echo 'SHA = "<git rev-parse --short HEAD>"' > pc/build_stamp.py

so a running exe can say which commit it came from. The default value is what
a source checkout sees.
"""

SHA = "dev"