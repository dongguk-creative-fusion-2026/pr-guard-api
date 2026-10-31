# PR Conflict Detector

Source: https://github.com/github-community-projects/pr-conflict-detector
Revision: 9c522e8da03f5efa738b7782dc9a61dd5ee294cf
License: MIT, copyright GitHub, Inc. See the adjacent LICENSE.

`conflict_detector.py` and `pr_data.py` are unmodified upstream files.
`runner.py` is the PR Guard adapter. It invokes upstream `detect_conflicts`
and `cluster_conflicts` with verification disabled and includes same-author PRs.
It uses actual edited ancestor-side ranges rather than upstream's head-side hunk
ranges (which include context and may have different coordinates in each PR).
The collector supplies the actual merge-base SHA; differing ancestors are never
compared as if they shared line coordinates.

No upstream GitHub/Slack/comment notification entry points are executed.
No extra Python packages are required; the two upstream core files use only
the Python standard library. Python 3.10 or later is required.
