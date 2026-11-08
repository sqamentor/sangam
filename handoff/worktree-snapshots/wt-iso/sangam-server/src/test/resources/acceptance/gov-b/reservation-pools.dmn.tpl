<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="reservationPools_defs" name="Reservation pools" namespace="http://sangam.gov.in/e16b">
  <!-- GP-26 / D-RECRUIT: the seat pools a candidate competes in (vertical: UR + own category; horizontal: PwD, ESM).
       Hit policy COLLECT, shared FEEL/JUEL subset only (string literals, true, '-'). -->
  <decision id="reservationPools" name="Reservation pools">
    <decisionTable id="reservationPools_table" hitPolicy="COLLECT">
      <input id="in_category" label="Category">
        <inputExpression id="ie_category" typeRef="string"><text>category</text></inputExpression>
      </input>
      <input id="in_pwd" label="Person with benchmark disability">
        <inputExpression id="ie_pwd" typeRef="boolean"><text>pwd</text></inputExpression>
      </input>
      <input id="in_esm" label="Ex-serviceman">
        <inputExpression id="ie_esm" typeRef="boolean"><text>exServiceman</text></inputExpression>
      </input>
      <output id="out_pool" name="pool" typeRef="string"/>
      <rule id="r_ur"><inputEntry id="r_ur_1"><text>-</text></inputEntry><inputEntry id="r_ur_2"><text>-</text></inputEntry><inputEntry id="r_ur_3"><text>-</text></inputEntry><outputEntry id="r_ur_o"><text>"UR"</text></outputEntry></rule>
      <rule id="r_obc"><inputEntry id="r_obc_1"><text>"OBC"</text></inputEntry><inputEntry id="r_obc_2"><text>-</text></inputEntry><inputEntry id="r_obc_3"><text>-</text></inputEntry><outputEntry id="r_obc_o"><text>"OBC"</text></outputEntry></rule>
      <rule id="r_sc"><inputEntry id="r_sc_1"><text>"SC"</text></inputEntry><inputEntry id="r_sc_2"><text>-</text></inputEntry><inputEntry id="r_sc_3"><text>-</text></inputEntry><outputEntry id="r_sc_o"><text>"SC"</text></outputEntry></rule>
      <rule id="r_st"><inputEntry id="r_st_1"><text>"ST"</text></inputEntry><inputEntry id="r_st_2"><text>-</text></inputEntry><inputEntry id="r_st_3"><text>-</text></inputEntry><outputEntry id="r_st_o"><text>"ST"</text></outputEntry></rule>
      <rule id="r_ews"><inputEntry id="r_ews_1"><text>"EWS"</text></inputEntry><inputEntry id="r_ews_2"><text>-</text></inputEntry><inputEntry id="r_ews_3"><text>-</text></inputEntry><outputEntry id="r_ews_o"><text>"EWS"</text></outputEntry></rule>
      <rule id="r_pwd"><inputEntry id="r_pwd_1"><text>-</text></inputEntry><inputEntry id="r_pwd_2"><text>true</text></inputEntry><inputEntry id="r_pwd_3"><text>-</text></inputEntry><outputEntry id="r_pwd_o"><text>"PWD"</text></outputEntry></rule>
      <rule id="r_esm"><inputEntry id="r_esm_1"><text>-</text></inputEntry><inputEntry id="r_esm_2"><text>-</text></inputEntry><inputEntry id="r_esm_3"><text>true</text></inputEntry><outputEntry id="r_esm_o"><text>"ESM"</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>
