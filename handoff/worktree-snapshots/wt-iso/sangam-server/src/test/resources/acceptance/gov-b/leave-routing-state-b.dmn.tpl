<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="leaveRouting_defs" name="Leave routing (state B override)" namespace="http://sangam.gov.in/e16b">
  <!-- GP-37: the same decision id as leave-routing.dmn, deployed only to tenant state-b: this department auto-approves
       only 1 day and sends everything above 7 days to the HOD. -->
  <decision id="leaveRouting" name="Leave routing">
    <decisionTable id="leaveRouting_table" hitPolicy="FIRST">
      <input id="lr_in_type" label="Leave type">
        <inputExpression id="lr_ie_type" typeRef="string"><text>leaveType</text></inputExpression>
      </input>
      <input id="lr_in_days" label="Days">
        <inputExpression id="lr_ie_days" typeRef="integer"><text>days</text></inputExpression>
      </input>
      <output id="lr_out_route" name="route" typeRef="string"/>
      <rule id="lr_r1"><inputEntry id="lr_r1_1"><text>"EOL"</text></inputEntry><inputEntry id="lr_r1_2"><text>-</text></inputEntry><outputEntry id="lr_r1_o"><text>"HOD"</text></outputEntry></rule>
      <rule id="lr_r2"><inputEntry id="lr_r2_1"><text>-</text></inputEntry><inputEntry id="lr_r2_2"><text>&lt;= 1</text></inputEntry><outputEntry id="lr_r2_o"><text>"AUTO"</text></outputEntry></rule>
      <rule id="lr_r3"><inputEntry id="lr_r3_1"><text>-</text></inputEntry><inputEntry id="lr_r3_2"><text>&lt;= 7</text></inputEntry><outputEntry id="lr_r3_o"><text>"CONTROLLING_OFFICER"</text></outputEntry></rule>
      <rule id="lr_r4"><inputEntry id="lr_r4_1"><text>-</text></inputEntry><inputEntry id="lr_r4_2"><text>-</text></inputEntry><outputEntry id="lr_r4_o"><text>"HOD"</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>
