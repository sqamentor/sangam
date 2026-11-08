Frozen E-46 v0.8 workflow-conformance fixtures WF-1..WF-9 (WF-5 negative; WF-8 with its called child).

Byte-identical copies (".model" suffix keeps them out of class-path auto-deployment) of
  tests/workflow-conformance/src/main/resources/in/oneifms/workflow/conformance/t12/e46-*.bpmn
Source: oneifms-trial0, branch wf/trial0-operaton, HEAD ab1b9588a355146c264f516069d5cd2bbbd4c7c4
        (identical in oneifms-sangam, branch wf/trial2-sangam, HEAD 51b78256837f1605a60effb48d6274a6707199d4);
        last kit commit 5e7b3f4a4280e7b3f9b40cc35d10e9ae06e6624d.
Expected results: kit T12Fixtures / FrozenT12 (re-expressed in org.sangam.server.acceptance.ifms.E46ConformanceIT,
which verifies these SHA-256 values before running). Never edit these files; re-copy from the source instead.

| file | sha256 |
| e46-wf1.bpmn.model | 167df15c261cac014df6692213422d34800ee391fd00d401d5b4d0533c730395 |
| e46-wf2.bpmn.model | fd838300cc2fd07b699be4840b28e6885af401abdbc21bbea53ca5ae1637639b |
| e46-wf3.bpmn.model | f0faf33fc5d943f62272640030732bf7801970bbd9b15cec5b2d8f76fcedcd3c |
| e46-wf4.bpmn.model | 30abead9e70dd73845b51a6d017a789235390405a2c51c8620807413560b8ab7 |
| e46-wf5.bpmn.model | b4572c2df517f78ba4a5f8c97400ec0027e4ab4ec2f82801e78546ecc5e59589 |
| e46-wf6.bpmn.model | ff7135baf9297dcb7347965696fd3364efbf29123302cc7ae6d570a6ed542f1d |
| e46-wf7.bpmn.model | 46d45aea9d1f280de36e6aaa09a17396da2bde7a7ad6b23a1be9f58706b8aa48 |
| e46-wf8-child.bpmn.model | 3926b185e771e5e004327fc691e091d0d3ada0a0ade35d3c0afa0c4fa64cf6e7 |
| e46-wf8.bpmn.model | 729e0bb1cf77fb841ce47bfe8039940dd3a3a87f4394deeba453b6a03a05db83 |
| e46-wf9.bpmn.model | 088e9144394c42312b57b56dc4d4370c8e618e5df5d98278ccef12ef8330d24f |
