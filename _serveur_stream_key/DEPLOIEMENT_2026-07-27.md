# Deploiement VPS - relief stereo + modele 3D (2026-07-27) - BLOCS COURTS

La premiere tentative a echoue : blocs de 2500 caracteres tronques par la console
(fichier reconstitue VIDE, empreinte e3b0c442...). Blocs ramenes a 800 caracteres, et
une VERIFICATION DE TAILLE apres chaque bloc : on voit tout de suite lequel n'est pas
passe, au lieu de le decouvrir a la fin.

LECON : `python3 -m py_compile` a repondu OK sur un fichier VIDE (un fichier vide est un
module valide). Ne jamais se fier a la compilation seule - c'est la taille puis
l'empreinte qui font foi.

## 0. Se placer dans le dossier

    cd /root/cineflight_web && pwd


## stereo_vr.py - 8 blocs, 5728 caracteres au total

Vider le tampon :

    rm -f /tmp/stereo_vr.b64

Bloc 1/8 - apres celui-ci le tampon doit faire **800** octets :

    echo -n 'H4sIAB5TZ2oC/61ZS28byXbe81fUbcPXzTvNpsSRH5cShWgo2lYi2QolzwOGIRS7i2TJ/ZrqbloaQcBsZ50AAbK6q2R8AwRZZJfdEPkj80vynarqZpOSx5Mgmge7Hn3qPL7zqNMPWOdPHRakoUxmfVYW084zmmk5jtPKC6FEerFQfnbNfv3xH9l4dHw0es7Ozpc/jZc/vT4bvj49+vs3I9ZnoSivWMaTVPGY5x4rE4wU++9/kBFze1u9J52tp53e07bfar1J6o0sVTiXSyWYyAu8JJiM+UyAnyhafhQsL5WezbP58mfs6jORsIDn35eCpQkr0lJhNeKsWP61EF4r5jKn2ULTW/6FluLlX2PBQpkXPAmEliN4RMtZxAufHQWSRVhXKShlSiQhOxy9+XYlDMuXHyHL8mPeIpFwdIT/ctD9uVD4defXmVB5sfyIPSnj+JUiSUTbZ8M5J061FpRY/msqCzorx7rHhH4OhFoIXraUCNIkL1RJWzgYSadpAq0qyBzhFK4mdFhYEo+xFIrYT1gOzpcfCx7MRVLQKr0FJQ9fn5yMXp2z4aPR2Tl7fnB0XhnJqhJqmish/EsYK3qkdYyziCFO4rFtmuYlJNxY6PmtUcK+EZNvxx5xGvAYEnM24yWYYMkjHhRyIUhPTXJaXE5qlsXmYs8YRSurpZUFdhakrDBNAr055xXjPnuZqtyCwGO0+dE149j1iECXStIDXi8JLcBInCYkRPSICLcsl7kogcsV3ggKhDoFMwBHGJfF8j8LSxQCQtczYluyqVAcnMU8YhzqvIZO' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 2/8 - apres celui-ci le tampon doit faire **1600** octets :

    echo -n 'CdW//vNf2PHRydH5iC1/ZIdH4xE7eMOG8JdX5x47PTij6eHB8OVorC0KPERSTMG8uAI0jUpCnuTa2qEkRiQQ0TodjU9Hrw6Phm+OD0D1zKI6kjNwD0EnPIdOYBIlZlyBQmHJPOJXghABfEDPkFKbCxDmUYSlVpHGE0HUfoD9Um2fR0YbSoRiIQlQWi8+g8t++WTrl/9Y/tP2M/ywGuxBGmcR3oQMeA+qUnJmvBJ8tHgJeCeFNJ4C4C5/ztkubTPeFpKrLLQUUDi8olS1W/nsmJgF1ddj0uHIYCESrTyDasBWqTxYgIhApsJjCzghSfhDahRjFRzzK0gVwURn56+Hf3fwgsLVTSFxiMcu08mFgYR51vD0tE4v4lujSBMF4Shp4rODMlihhnw2kxSngN+WtttaGGR4pQDW1AJRiWJHl2eyWy13b+hEGd52NTWPJLmEORDhoPZAgOMxIhmo9lsMf6ev4cmaguGIVX+d/d8rD+20QR3naqovRueMdRfqy7DbWDJUwTYgmIgSUca1UbeLUPsRsTmbk5q7NoKTNdoNeisuuzeGHNH727PXr5gbSj5L0ryQQbvVOnp1dn5wfHxwfoSlvjZmCoVVngCj5zkFu5A8mGcZ+yAmLCslopbegilKTkZDU5XGrE5aMFKWqoIpUqJiPF8tXZg5/RJRkEkQlaGw0+7mvrbOhi1Lj4BQPad59UTRlFMSrSbKUoatlmZpypF7MlkxdHB6pA0L/L48Pz8dXQUiI1/32FhQZinW3vLhDBkCAXBg3395fnI8tpOeVmo1arWssIPV' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 3/8 - apres celui-ci le tampon doit faire **2400** octets :

    echo -n 'IS60fPH8aPjyCJFnAI79jBdz/xKB0q0GCDcJj0U95pOcft2Li6mMxMVFu+0xp+EHTrt1EaXBe9Cr5faPMUGHPWBfwxMhD4MxsaATeV+n8VjmOuJcIkJQWskUTSBGDQ9fUcwPKeZnXEd3QgG8uYFBUA4pHAeRjk3aT4Q0cSTWyZOTGv3W+cvxaATenHlRZHm/2w3C5DL3gygtw2nEkUMQt7r8kl91IznJu1Um7Krt3jM7imWCGVi9FSKIXEQIyG7bwKxQ1+aB/j7IYs7STCRupWSoSjlI74kpqgaOLqqcNiFwunqR/pQoECo1ovwo5aE7NU4kNCRYjYzVW/aNm9uKMREoYi2seIszCF7b+wvm+JhyWuusYgpcfvgdXGrWwjLO3NBjU9oPs4kLngdSDp7zKBeGYyBHCR22DPGKg7Yh8wBYAKPgXTCOxCO/1+n5kuuCDc49lcGc/BzgiAECBFX7BuT8GwNqH5GhcJ1GbAEMeX6NrECaCGA1dWEWXGUcqV951H2Wm0BR/APlcrtbQ9v9rAU4YXbNcd2drS3oE+VDlpsgJ6MIuJ1EwjHkZjjLnfgzAQFWMRraThVznLYP7MrMHh1u7NUx/P6tEvVDWoA61ugh/AybvR7YXJ1POb8+wSQrUoXMnZo8GB80yT5gh5TipqnMV7W1yYbQpkmOdYnhQ/3TMte1alVPIY7LhRJU1jeI/vJvVbr+5b90lYWyKNE1PEUDXS9Zr6fKUChdglGZTSIEYF9PADxUWyw/TlG4NqgDXxDOloz+71BRncqvdT6nmpLOBhdG4Pr+' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 4/8 - apres celui-ci le tampon doit faire **3200** octets :

    echo -n '0jcyW+at2tZRhtQL1Uzh3kVlVZOOjUm3/K3PIc6SwE49lUtCCKUXn/6347b9ubh629/uvVu5uQ7PKxL6SjSowtja9FvQe4e1m7XI5OiCwukjZ6qKbTNlgDg21hoi/D5HKToHQL11Ag2Y99nsnkWDa+T8jTWrnT4l7iR0aeixXoP8bf1URT+Sw0hl42MzKbo3Tl3YkEASkcwpVYRnxxQ+D3OHPaSF23Yj3GiZ1yoZ2rEWc2QyTauQg0WtLRtqxErdmhKW13xWfC6i7FAeMVpGeYIUWDqfFlG0q3xAJbOrZRRV0COzgRvxe6zYagBO3ItWI0Q8u6CY5qxXtA9zU8uSOsXbJgTe1a+Fv+s1A453TYFRg+394fD18Py70xGbF3G039qjH0Z3s4EzVQ5NoBjBTywKThdKlYuiSm3VNFU6Awf3mw9UTTm4w2jvHjgfZFjMB3T1CURHD3C9TJCMeNTJcYUSA1yKKZzpEUd0HyQpkYVKI7H/0NWqbeemVWJU++XhXtcst/ZygBUenqtg4GAzlRnt3Nnf65oF2lFc006mxfMmaXjNbnCBUTOZ9Ld2EXZC3abB44QH72faQfoPtrYwkSIqIsh86M9lGIpk1xTEEK0z5bGMrvsdFLqR6OTXwGvsfRXJ5P0JD8708Dn2ec6ZmKWCvTlyvHE6SYvUy1FhdyCvnO5SRyZV/QfTKZ7JAQOeLBCab6ixgrx/3Z9QxDFrDxDAbxA2c6kD2VReiXAX0XNaEOuKgKZlSAuUAk3BtneyK7b9BP+DggNXD79AzbFwcz4VHVRuvCOB' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 5/8 - apres celui-ci le tampon doit faire **4000** octets :

    echo -n '96Jj3m23dy1ga21ALsFVZ6ZQlMKqLorCBMWswrOnZhPubnn0j//sMV5mhbgqOpzu0n26qQplBTA+c1eGIs2I3zui3BFg1+g+lz+I/vZjjD/HZ5O3p4/bXoNv8GlSkOqIBcZ5P8Hda43TCXhtHPiUGKBlH6FGrK/1aC3NeCCL677/tLdrAdYh2TTnxFpnLrRs2/7OY0NqUkLjqDzXoLctnj0Tj9fRMcGVUChjYHrqkIhl3t/eIuK1pr6Epnq9SjMNBjUPQalyULRyNznoA3Dke+EGKzs7O/VriIS8jAqrIooDMxFTBr9rUQ0nYrbC8TQSOF9joiPhG3mNjMsSl9bpdccGjGp+07LGHzcF+gHADcVV/8/EFFzeePpe1wYs8nX8hHLBZDhwViw7+8MV+7gpmZTw64//stfF5sYrJq7v701WgWivO9nX60GE+9TAITA4+8ufAq4KS7DuWdBNLK8bZ5Dpoc68bb83ZbE5a/PEUjoUq/YsMmhqUiRfK4dVJtofUe9N0VWNAqHZqN/Z5Eq/HeczTZGx47p9k9tOIORatX6/HpNoqL6Cexqsue6eZsrWpf7eRBmiZ7h0NpsXadnoXvx2J5Ei+qo7Ru063RkzNYnpJiOX6TYThLN6sj9VdF9wxY5OXly8oAT40NUpFPHf05OHq8kQk7t6dw6EofYJkLAU9xj1plFqgNU8m+PhRfVwaLZHMALKRGrzFeYhVJyy9JTuaR6Dw+nZ7Jp+d1utaZnoJqMl46Io8mxP1qN2m2jrqrDbZWe2/45UTM09utG7OiWyzjZZ96pt' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 6/8 - apres celui-ci le tampon doit faire **4800** octets :

    echo -n 'mq2mB2k68gitdN0LBbVroL2jV/TZ4Gj0ZkxKIoZnIgUnifjA9I3dPzNiiRR5Wl27j+lW9QT/7WzpOI/tvj7U7SAR0796mijhsDVK5+bwY9ypqQNiLtdaPBKLPk8gRCDMhVbOik68RuVE5HMXp3obc1/xXAYnsL9CaeBShs76moVbk5BiH4FEqNxHaHGNPhHD9R90uU1dGAG0VY26XjWhi55WXfDEu63bhpGoFHGNRTQw1lVHM64+3cBlbfUUzFDXVC7EUK+6T3tU2yRCfUN1DuuawUsd9D2UeaTd7S2jd/DcaLlv+onmXBufT+kWrxv7Lw7eDF+OfLhcJpTp6FV3KkMwpJ6OvrSt2qlhWiJo0I2Oeu48Iucz33X0LbAOfn4tZKVnkVC4cQkP1pDkpNQKH7AeiVArEWFBK5HKcLfTqbbtwSHaOA+pUBFgTOawbgYi1kG0+2roER3s0obwkdJcu1crzHrl+nuHMPQn3zs0nFcevma8b8TkxfHYrgBt4BewkzwH5hTscavPrF4lzJ0itUVjaoa5pppdTdzZe4YU5a6Q4DWBsL75Slk9UwVPJ9NqmAYlZRKfEpiPEhO7h3MZhW79XpjGo0inm3UhiSA0MKKS5pg+geBc10EWooYfzILa3FsZziJfg2f0ig0PzsyHR5sBNAqrr0a00vxgNNQBWgZS9x8Svvz3YvUFxvQDCDMB5Goyh9uP9RaDNltsEnIC9sc/ssA38/nawI9EMoNLDQbAXru+Vtfrb7fe3QPbzU3bm5t692xaUbIJ995d23d2VQeai3RtQshr' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 7/8 - apres celui-ci le tampon doit faire **5600** octets :

    echo -n 'DfXV9VHo6nTc9g0ezk+OKUONbXznJeUB7aCp9BvpmhTtsC8sE05kvuxQifl9ufxIqWL1mQopnaIDNC6DMqKvwHS2zuy88dlKf8Oi70W+Q2zfGhTdRQ58GVi+FzM2WnAdBCHHpyLfbnN3mYUI8acqvTSf4hDwlbxyre7+Vz50WycYETVBtnINWhfRPf5gK+Aw/ZCsiUa5ucrv2hlNehe+6ZN9u2vSfDX+btcy8VuHxLhE3jmkxvwf6LS2TU5GCVRxdAbYV53KOmCjzf5EGeTJri5Evmisf0fr1/W6oaGLlRP67BDzK7fz7LFnRzJxaYANdFX7jHi1lj8pXZltYmOtPtq9J5AeJMhMtP04TTMd0RTVCs2sbCYt0rSamiFE5qeAJX0DTWaVLgkG2VziXFtRQNg3hYzgn2J2no5Rqfx5C4oiuT1GkeTTG2GA9hpoozR9f1BQ6QQlazXmUCOOa1fjIM3dYg7KjT00R3vWO3Hm7zdI0RikDMSbqjMP7lr9uqG3Va7VavlkEGrchKj3jVuTb29qFI/oIuxUvkUN/U/SMTcTzaq2UsIXcsaLlMxUWWbiU9E6NNc7Ij+srhuo7nVjuUAoWH50dhteQII3icHmZyaHofihDpPA6TKO6ZvYQnQWYMIv5iJxV1BM36/5mR7+H5ix8Xzi1zfkCtp2Pk3gLfqr3d0QuSGE/SRiJdkQwAN7qe5T8ui54FRpoxh560QpInZnCgwq5x3cqUbTpsA5Cdj0Ewqi9qi8XXkidFu59W3LVL+7rUa3rGsvzV3dDHQcah/e1I1r+0Xc' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Bloc 8/8 - apres celui-ci le tampon doit faire **5728** octets :

    echo -n '0X02jLUPeaa9jKFpKzv6Foah/rXj0I7D2zvdYPudfLMPTE3X/4c+sCn7m593XWdvvr0/rtrABZhZkGkh8/Y+7IAyqSjziyANxWBna2etQbxGZ60v3G79D3PPaG/2JQAA' >> /tmp/stereo_vr.b64 ; wc -c < /tmp/stereo_vr.b64

Reconstituer (le tampon doit faire 5728 octets) :

    base64 -d /tmp/stereo_vr.b64 | gunzip > /root/cineflight_web/stereo_vr.py
    wc -c /root/cineflight_web/stereo_vr.py
    sha256sum /root/cineflight_web/stereo_vr.py

Attendu : **9718 octets**, empreinte

    c82edb740d82f61743171873e1d5f084277f191950556dc0ab1cea0c656a7f08


## modele3d.py - 8 blocs, 6048 caracteres au total

Vider le tampon :

    rm -f /tmp/modele3d.b64

Bloc 1/8 - apres celui-ci le tampon doit faire **800** octets :

    echo -n 'H4sIAB5TZ2oC/8Va3W7bSJa+11PUMDBCdiRacuK0V7Y863aUxA3/jS0PdmEEmhJZkirhX1eRij2ZAH3be7t7tVd9N+NeYF9g7kbYF5kn2e9UkRRlJ+meRQPr7lhiserUqfP7nVN+xDpfdViQhjKZ9VmRTzs7NNJyHKcVp6GIxNPQz27Z37//D3YxPDw7vRxdXB2Ojs5O2fnrs9HZq4uDk5PlD6OLo99dDVmfhY+LhL0VBQsFy+Zpnmq2/JFhDMSWf4kEe/qilfCFnPEJHtyt7tbzTvfrztbXnt9qHQsWqjQRLE8LhQ9e0BcWFkwXb0XeZtFjnmVMJItUChYJXW0hA9lmIscQ00ItBBYpEaSJzlUhc2zfirmMIj4DaXGTF2p5x74rhJmfiSDnOS05P7g4PLu6GLGQJ5rpNGEB1zSNDi8jlhB1li/vouVdlqocJ4wKDXZyJVohuNHZfPkXJXSbZmNqiHkRD4QliM0iKQqc8+//+SP+Zwe/H16Mji4vhyfD0xF7MYSALy/BwOHwss2W37Pjo4shJh3Yl+cXZyfD0QhDv7s6O2Ik7sMhuzw7GtHcq1N2eHxEdPqtiK9OH+QS5xCahIDjREERsWPs8aLNNDEVpHFc4GCx0IWCxBciIClrLeKJkZdRaMaTVPGY+61zUsj2s0rw0PKzHXaS3bTZ4dnxycE5e8LOMpGc/P4S72KehJAOy28zCTnG9H2H7KH3nL1KaTGsB3prkRwhGqXZ6+EVpECvDs+vfHaVkEVkEXSLZVu0CsxOheLQ1iWDaHPFF9AtTA+/FjKk' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 2/8 - apres celui-ci le tampon doit faire **1600** octets :

    echo -n 'V5qzeHkXpxInAnUIPlNpILSGtjRNyAsYQMYVjnp2dsLewTaEggUlbLa8S5Z3ikdWPBCLJikEEZcKmjsUZMgFKJJIwzQJIMFk+eeCZSSY5U8wBfb67PR0+W+jIaxWEYtZKnHwfouxDkyarCLmShFZsHwyvMSJWbTGMtlKksYTPEASwf/8O4kmlDpLEzkhu981xLQ063gwl4nlSCa6mE4leM+FsUIeBCKDpX5XQBfY4qfYWP26h7pK4ujJY1AAXcYyocKiTfMq6WLuIo3MFmRY8LHlnYedJYkI7D5e3sGJSEd/EMmY5zlULcZwBQ2xQPJ/sIcy1F/AYkkyYD2DENokE3IM2iTiSSBUw1YXKakKdGMQkopUpGUkMKuUwUNjT0QlYmGcnKQEkaVFLiPj1kZMOY/gxpoNR00ZBjyjwGRj0bqQ6Oyjs6tv4TyXKyGYk0AztJExwUwUeWkHEJ7MydAoSuRchW0zG+7WqvaL6HiCzhc9nsMBROmWSkw5mUJk5A52LsA+DtE3Ejw/uxyxTZ7JzSpEs/qns8/iIsolrDtnbi6JkSdsCpnp6zcevf5gF41l+NFQewURsJrS5geMN6llJgpAjIVcSLYJH9MFj2BiJOwGgSY7KyIgYE3j28uz0y/NLp/8WTShRTh4FbLbpfFCuOJG6ly0WkdIQwfHxwcmD/VNnE2hjzrOhqlGPFHGokzKeC8mLCskIrOZgiFKalaaU5XGrBakjCm0M0XyVozr+s3YDpkltF4mQVSEohx2703zTAZtldTewuqq76muvuk5mWT9VEzKKFWN' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 3/8 - apres celui-ci le tampon doit faire **2400** octets :

    echo -n '5HMlOCXmekDGovpeFDJstQzvUw5rzmTF+sH5kbEWBLTXo9H58IYiAHTVZhcCZqqRR6+yKOXhSxhFm5W/UxWvUfPhvBlZua7ovh6dHF+Ug22jz9UTEameWq1SeIMVK67Xao2/ObgcYjDVfsbzuf8WYdGtHkKpEh6L+plPNH264zGZ7njseW3mWBnrp6HjtcYvjw5fHw0vfh2CPmmIqEZp8A4ka9H7xxgg7kevL4bEvTPP80z3NzeDMHmr/QAeHE4jroSPVLrJ3/KbzUhO9CZRECC7qXpbO+VTLBOMwCwesUtRUDTiRSc0QZJSKQURjNkQZiIhu0BK99nhPJUaoXYCc0Q4QASj4GDSaZ8SjIlpOAfoLnhk8qghGVmSFP0oQgdzJHcKY4fAHQdHp234tplUr0JYm0LVFAK130JyHp8cnY5fneHcX/vdlkF8lzSGgd5WqwXUM2XjCMy4nnWmXN3aL/TzXuZzlgIOuJW2IHPlUKK1iHPgGMTpeORo09VC+lECOC0xruOTtbpTz7wXxp5ZbdarVeWKDx8rxkSgiLWw4i3OwHdtOE+Y42PIaa2ziiFw+f4XcGlYC4s4cxHZpzSfMNSY60DKwUseaWE5hgkqYYCgJV5x4FV8xvytG0sQ+eqrYM7jTJcMG6aMSa42BSLO6RRW6PWwIKPFK38mcqLlsVSRJOr3fpGFgLluucEavWsseAMCoh6tREdvKzZX2bxSNkLcSQVa8hQZFYj+VepRrr+PXnxYvDIA4cWRhTtInorSo7VCvAjFAilR+RQ4ifosBUtdmN0X7MrZ' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 4/8 - apres celui-ci le tampon doit faire **3200** octets :

    echo -n 'pJi5GQu41jR1jHl9yphg1cAYM/iVTO6/ox85ta99xD6Va9rAdU5EPKJT9R3v4YqaQ2A7t1ybRTJ3vese8uwm63Wf7Wx//bzk//4PhMPf/Yw9Qy661TRsBxh8PEudPqWnJHRnaZv1KI4FKbkvxmFqQVaMA7wGJ2QDvcobLP4ZZ1AjAkdTh8eIABOZUEwxiP4enqLo0qFg1UBNv62UVLFWMw1eophn4MWmN//9XAYQZjnsMcSxJM3ZKeq89moVaTNe6AfLXsCp5PT2nCD0IcXaT1GoHX5V9MF2jUeVSGB1WPN5OOfL/4YxlDUL8EZZtPhsZEtPAxMAQ6cEfpd3oVzeAYWVawnsEarJ6AOOkptiEUgZIdtEZYtDSlDcJ8R3X6jfFRJ1rfGQ4oYZ6Gd1rUsIA69KQkXodVZje+BMoOPElM5Ulz2gGlNV6q8dVcaAUPp+lizFAtOx7x2v9DIL9z87u5zg1IEt5u8EUq12yzdti9HG6bvBSBVlABQkKuLhula46wBDcdQdVl51KQ8Xvl5zmMpwsPlU8JwCLCp4xYM8RSZZn9rpIMRxZEkxJuaxZu0YNYtOOPHDieM9WG6EUa21knkw54iGL4AOEKo0sgPARACgoTix2Guw9KZB33WgqblQ+GcOG6TK4quQbOaLpxY3c17oXC4E8kRORH69Y6+zCAwtyYyShlKU1DYoLArxJS7xkYn/H4UgsGVFXk2qaD4445tWlQhkmyFiTwgYtVkQI1ciKYikICUiQ1prbYT8VX7Gq3zgoMJFgFWkNTN3UBPLgnxA+aDXBbrY' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 5/8 - apres celui-ci le tampon doit faire **4000** octets :

    echo -n '2mZfMek1Um0zh5ngCZdYwX5fFYkLdtpUhRpDtyczjtQ24B8Dg+eg+vR5t+u17iUw5dtgDNAi2G+QOh8mrQcnIUh4T2n1j4CJFmrgbGhCmUR0I3TYRkN2zR29z1AJsZOMBi6cJYfLKMpJE8fxrjs73W7/jR8KWu2WEIsC0ixJlXA87wE9u9kK0tjESaXHaZq/pIw4VCpV68f+zJHr05m0iFozoSKTgvWGpkNCD9fdN17r5/dvKHBkVTS8yZB/wn+MDyNlZJqIS1PPIvcv74iTUtq/hJUaQxACEjf/Bwbs4VcaFjfeZzd+xI5XRTr0jNKPuWUe9UzGMh0elVOvltsuSJAu/5qLAjN3Ge1gm06M2kXUB7HtmpJ61IQltsRZ/ggcKRMckHDJY67U8qec+sQsKYgJkpvSbZNdie6L5Q/fLr9ftSwocYq8pA+tFQu7J9ibLe/SGAkbKYlxyu7LOzgsZoAvm1HvCxBlLVCnqKNAOQCt2UDQ63YbPgHEgilNNtda1Lv3JUmHFDeAF0VedpkCkeei6lQ5hMr/2RbaPgJ3Dijc7KngPde3ScAIFwUoPtXYvrItIcCsnKIPlf0AunVvHiHY9or6sDudX68aBVQf0Kfr+75XBkcEHQJiZgH5dQREbh48tsdWxWKjQuNIJ+tdCffZ1ha8HiokxKMRY6qGm1E5xEmuZYyATHNF1dol9AHGqBni069nrufPxc11v7dl433VCbqHaEwvok2rvX8cJTVhT5mTPoV6TEVEJUydeKam+jDirUUCL4TR3N+5IlsNUgo17YypT+vp' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 6/8 - apres celui-ci le tampon doit faire **4800** octets :

    echo -n 'KwkcfM38t9msGS1XxZElTcXsxBZFOiVnXA8LwNnQPHH9MF1MUHqCM/6ew0KnPrVE3B7b22NbXe9TJRQZA63p/0zJ0/yxPPnvgaKFS4ubpSkJ8MmA9VplrUEaapahVh2mIH5Q3ph3ZT/XLruu6qc3bH/AGt0N6i5SGVzhmTf3/N24zMD8bpfWOTDMtY1rmZRP6dmnX67XTIUrbgfmYsiyOcBHY5KJJ+4KV5AsK85FBI9xPtlUX8NLNgYBxCkTXUx39hOEqsZz86JgjQ5FrlK7q8WN9kPdGBuZby7q5ZnIB2ulV5thUA/WKzBqvYo4TayH2EK71FJZRDZbiu4Hp25Toyg0lJxCRfjurBrHNl3Ry0+DD4MQ12uksWmsCdApz/axGUmpe+Lca06D/Me1cErqqqIpXppYWkZEsWrN1J2YZqgUPxMLu89sV9KEYwlglSSF83khiV/Ce6OzvnaMKQpsucoL907y6bi0ipt197Sk2zhktcLERF2GIe8XnbzKgdQSovYbFcj1tde6IJot5zrUxYjKfJzfUjo27G3OIgBLghHq1nkorS9ome49fgUtW2abvXPX2Zv39k9qJRuUQZa4t4lxgAe4Rl7oMeHiAeTiNYKfsHw3Y0Cj0VeGiNU8slTMGAw+F0EaQK686QwfV1RCBP3hvxwcjuz9dCAMrDJIWZib2b/9F1tQGzsSf4S0EMUT6vr/7a/lLVdJu7zIo7WNmznF2Vtu7g/Lu2mUnEj4pPo0khZwUTYYr87kVi0NxvZCuaA7Wa0HDl8IlTv7tcT3JvsX9y4E8Y+8' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 7/8 - apres celui-ci le tampon doit faire **5600** octets :

    echo -n 'nhBmeSVb/pWAv7c52d+bqNXqkzI+Agq7yBae35uWV9XV9Wwbb4Ks8ELb2XS157OjiC4mpxyZaAVkXLJ6v0vr/bU9wCG13DZcm0q8dcRTXy0KTdyZPz54eMlJ182Qob1onCI/r3q39XVjddtY327yEIUlCH/mkrHkbhPC3YesEVlNx5HajUKXhmcTaJt1TdsxK5ovyy6kefmpeOxAIJi/SrwgUXZ9+pXF1l2grvfRI3+q9G/T11rTcd2pHGfvNy/ODkf/ej5k8zyO9lt79EGanw2cqXL29+ZIWPt7MbwC4Q0IHHmrrDsx2QwTsBo4Cyne05WXQ8qg7QfOexnm8wG1qAPRMQ9tuK7MJY86GiYuBj0iAowQif0NC7I9bZS3Qtd7m/Z9a0/nt/TJJml4yz5AQWomk353l0148G5murv9R90J/gt3wUSEqvbRdDrdBYxM8s6UxzK67Xd4lkWio291LuL2N5FM3sF8L83jS8wzSnAuxSwV7OrIaV+kEwi3TbrvwMQkyGU8NH/5s7WV3ewyRJF5D+yYTbT8o+j3/onGK/ZYlz0r5/nmgukDgCYPZH7b95/vNpc9XS3rYNM8jfu9nWrpxFzNf2BzIWfzvG+2aJ57a2sLA6kKheoogI1C97dpTgpPn0bp+/5chiiSar56YAqs3aMvsQPljojf9imSvNutNkRhtrGxvmVP7OyI7V1mNNuHZwa5F9IkomciDKg1FzzlW7xXs9nvgQONwBWyR1/zbb7VvX+AXpe4q8RNHNOfLzRFZiQLHYpOxaa/vV0LMU+zfu95dUKK' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Bloc 8/8 - apres celui-ci le tampon doit faire **6048** octets :

    echo -n '6+t6MqtrZexs07y9TWtmyCvG8MnYyCt6KwM1KafVDKekV2f/YWxSYvnngkISRYe1FUbc8C6JjejfgwkmCzEZlt9A3IBVryK24ZZO7ml4RqBklu+34JxHlE8WPHKnRWKinuvRPcNU5HQzsI50NkwSRjr08zlVodUK5X0oo4Uyd7yut/vx/hzhfShrxaCgzidFomFkmqDf3B6FVRr16U/HDm1AYIMywLnCtw0U9lskqOUP5o4VGJU9YdUbD880zdwX/OlPTFj14ZuDum13fWvkVnV7iTNRf9t1Klt2CDBDl74xUEqGwoeNEpGuRzeZGxsOUcLZAmoSN0X2EXt8bLPtbreLbzCKUsLILmQQMAGKllXAN4axisf20YCMZpvgy5H7M2jcMSJYrbCPhvY6XqHNzBZB3qCPhy8RL20IC5q4gUBlVUEA6f8vBlYQue8oAAA=' >> /tmp/modele3d.b64 ; wc -c < /tmp/modele3d.b64

Reconstituer (le tampon doit faire 6048 octets) :

    base64 -d /tmp/modele3d.b64 | gunzip > /root/cineflight_web/modele3d.py
    wc -c /root/cineflight_web/modele3d.py
    sha256sum /root/cineflight_web/modele3d.py

Attendu : **10479 octets**, empreinte

    aff3418b7aee5531b86e1bd34c12138d05b64ef8398f8dea5b544174372f7ded


## Brancher les deux routeurs dans app.py

    cd /root/cineflight_web && cp app.py app.py.avant_3d && python3 - <<'FIN'
    s = open('app.py', encoding='utf-8').read()
    ancre = 'app.include_router(visite_vr_router)'
    imp_ancre = 'from visite_vr import router as visite_vr_router'
    if ancre not in s:
        print('ANCRE ABSENTE - rien fait'); raise SystemExit(1)
    for imp, inc in [('from stereo_vr import router as stereo_vr_router',
                      'app.include_router(stereo_vr_router)'),
                     ('from modele3d import router as modele3d_router',
                      'app.include_router(modele3d_router)')]:
        if imp not in s:
            s = s.replace(imp_ancre, imp_ancre + chr(10) + imp, 1)
        if inc not in s:
            s = s.replace(ancre, ancre + chr(10) + inc, 1)
    open('app.py', 'w', encoding='utf-8').write(s)
    print('app.py patche')
    FIN
    python3 -m py_compile app.py && echo APP_OK

## Redemarrer et verifier

    systemctl restart cineflight && sleep 3 && systemctl is-active cineflight
    curl -s -o /dev/null -w '%{http_code}\n' https://cineflight.ca/vr3d/inexistant
    curl -s -o /dev/null -w '%{http_code}\n' https://cineflight.ca/modele3d/inexistant

Les deux doivent rendre 404 : la route existe, l'objet demande n'existe pas.

## Retour arriere

    cd /root/cineflight_web && cp app.py.avant_3d app.py && systemctl restart cineflight
