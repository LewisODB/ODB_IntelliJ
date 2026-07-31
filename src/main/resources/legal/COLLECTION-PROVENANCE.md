# Collection compatibility provenance

Lewis ODB commit `48bbb7dfaf31b129906056b422c7b97f5dde77e0`
replaced the former collection implementation bodies with original
compatibility implementations based only on public Java 8 collection APIs.
The retained class names preserve ODB callers and serialized data.

`MyArrayList` remains ODB instrumentation machinery. `HashMapEq` preserves
identity-key behavior. `VectorD` preserves identity search and its
non-traversing debugger string while using `java.util.Vector` storage.

The historical prebuilt `LewisOmniscientDebugger-1.5.jar` was removed in
commit `0ffedc6`. `verifyCollectionProvenance` rejects that binary, former
proprietary notice text, or missing compatibility classes.

These changes are modifications under the repository's GPL terms. This record
describes project provenance and is not legal advice.
