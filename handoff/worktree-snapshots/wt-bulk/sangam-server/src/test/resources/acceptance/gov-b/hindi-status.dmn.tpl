<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="hindiStatus_defs" name="स्थिति" namespace="http://sangam.gov.in/e16b">
  <!-- GP-38: Devanagari string literals in DMN input entries and outputs. -->
  <decision id="hindiStatus" name="स्थिति से परिणाम">
    <decisionTable id="hindiStatus_table" hitPolicy="FIRST">
      <input id="hs_in" label="स्थिति">
        <inputExpression id="hs_ie" typeRef="string"><text>status</text></inputExpression>
      </input>
      <output id="hs_out" name="result" typeRef="string"/>
      <rule id="hs_r1"><inputEntry id="hs_r1_1"><text>"स्वीकृत"</text></inputEntry><outputEntry id="hs_r1_o"><text>"APPROVED / अनुमोदित"</text></outputEntry></rule>
      <rule id="hs_r2"><inputEntry id="hs_r2_1"><text>"अस्वीकृत"</text></inputEntry><outputEntry id="hs_r2_o"><text>"REJECTED / अस्वीकृत"</text></outputEntry></rule>
      <rule id="hs_r3"><inputEntry id="hs_r3_1"><text>-</text></inputEntry><outputEntry id="hs_r3_o"><text>"PENDING / लंबित"</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>
