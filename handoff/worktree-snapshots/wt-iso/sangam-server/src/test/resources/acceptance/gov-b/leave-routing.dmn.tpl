<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="leaveRouting_defs" name="Leave routing" namespace="http://sangam.gov.in/e16b">
  <!-- GP-29 / GP-37 / D-LEAVE: who sanctions a leave application. Default rule set (central model):
       up to 3 days is auto-approved, up to 15 days goes to the controlling officer, longer or EOL to the HOD. -->
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
      <rule id="lr_r2"><inputEntry id="lr_r2_1"><text>-</text></inputEntry><inputEntry id="lr_r2_2"><text>&lt;= 3</text></inputEntry><outputEntry id="lr_r2_o"><text>"AUTO"</text></outputEntry></rule>
      <rule id="lr_r3"><inputEntry id="lr_r3_1"><text>-</text></inputEntry><inputEntry id="lr_r3_2"><text>&lt;= 15</text></inputEntry><outputEntry id="lr_r3_o"><text>"CONTROLLING_OFFICER"</text></outputEntry></rule>
      <rule id="lr_r4"><inputEntry id="lr_r4_1"><text>-</text></inputEntry><inputEntry id="lr_r4_2"><text>-</text></inputEntry><outputEntry id="lr_r4_o"><text>"HOD"</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>
