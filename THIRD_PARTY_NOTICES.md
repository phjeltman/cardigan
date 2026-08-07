# Third-party code and ports

Cardigan-authored source is licensed under the Mozilla Public License 2.0.
Some source files are ports or adaptations of separately licensed upstream
projects. Those files remain under their applicable upstream licenses and
retain their copyright and license notices in the files themselves.

## picohttpparser

Files under `src/main/java/dev/cardigan/pico` and their corresponding tests are
Java ports and adaptations of picohttpparser by Kazuho Oku and contributors.
The files retain picohttpparser's MIT/Perl licence notice.

Copyright (c) 2009-2014 Kazuho Oku, Tokuhiro Matsuno, Daisuke Murase,
Shigeo Mitsunari.

Cardigan distributes these files under picohttpparser's MIT option:
[picohttpparser MIT license](LICENSES/picohttpparser-MIT.txt).

Upstream: <https://github.com/h2o/picohttpparser>

## simdjson

Files under `src/main/java/dev/cardigan/simdjson` and corresponding tests are
Java ports and adaptations of algorithms from simdjson. They retain the
Apache License 2.0 notices carried by those files.

Copyright 2018-2025 The simdjson authors.

License text: [Apache License 2.0](LICENSES/Apache-2.0.txt)

Upstream: <https://github.com/simdjson/simdjson>

## simdjson-java

`HeapUtf8Validator` and portions of the Java Vector API investigation were
adapted with reference to simdjson-java and retain the applicable Apache
License 2.0 notice.

Copyright 2023-2025 simdjson-java contributors.

Upstream: <https://github.com/simdjson/simdjson-java>

This inventory records source provenance; exact upstream revisions should be
recorded whenever another upstream synchronization is performed.
