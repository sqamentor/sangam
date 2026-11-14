import sys, difflib
a, b, lst = sys.argv[1], sys.argv[2], sys.argv[3]
for line in open(lst):
    if line.startswith('DIFF'):
        k = line.split()[1]
        x = open(a + '/' + k, encoding='utf-8').read().splitlines(); y = open(b + '/' + k, encoding='utf-8').read().splitlines()
        for l in difflib.unified_diff(x, y, k, k, n=0, lineterm=''):
            print(l)
