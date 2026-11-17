# SPDX-License-Identifier: Apache-2.0
# Owner decision 269: each pack declares its documentType-to-slot pairs in its manifest (documentSlots); the server
# reads them and has no table of its own. This read route publishes the pairs of one pack
# (sangam-rest PackDocumentSlotsController over the server's PackDocumentSlotsPort, PackRegistry of sangam-packs).
# Loads after packs.py (name order), whose PACKS and PACK_ID it reuses; the /packs/ post-processing in gen_openapi.py
# adds the platform administrator group and the 501 answer. Executed inside gen_openapi.py.

schema("PackDocumentSlot", "One documentType-to-slot pair of a pack's DSC steps (manifest documentSlots, decision 269): "
                           "a PDF uploaded with this documentType on a process instance of the pack fills this slot.",
       "documentType slot", ["documentType", "slot"],
       descs={"documentType": "The document type the uploader names, for example NOTING or SANCTION_NOTE.",
              "slot": "The case variable the DSC gate reads the document id from, for example notingDocumentId."})

op("get", "/packs/by-id/document-slots", "listPackDocumentSlots", "Packs", "Document slots of a pack",
   "The documentType-to-slot pairs the pack declares for its DSC steps (decision 269), in manifest order; empty when "
   "the pack declares none. 404 NOT_FOUND when no pack with this id is registered.", PACKS,
   params=[PACK_ID], resp=(200, "array:PackDocumentSlot"), perm="tenant.admin", errors=GET_E)
