# SPDX-License-Identifier: Apache-2.0
"""Procurement mode from the bound tenant profile (owner decision 225, IFC-189), used by gov.tender and gov.gem-purchase.

Whether the case's department is scientific comes only from the case's bound tenant profile
(procurement.scientificDepartment; GOI.yaml lists the scientific departments, the department layers carry the value),
read through the existing read-only config.profile operation profile.get. The path is a literal of the model, never a
variable; ProfileValueDelegate clears the variable before the query, so no start variable, form or default stands in.
A case without a confirmed boolean is refused with a coded reason (fail closed). Then the profile's own
ifms-procurement-mode table (the tenant's own table, deployed from profiles/in/dmn/<government>) gives the mode.
"""
import os as _os
import sys as _sys

from lib import q
from common import reject_with, check_conditions

# SL-102 single source: the procurement-mode bands are profile data; profilegen keeps them in skeletons.GOI_PROC and
# writes every government's ifms-procurement-mode table from them. Appended, so packgen modules win any name (as
# gov_ifms.py does).
_sys.path.append(_os.path.join(_os.path.dirname(_os.path.abspath(__file__)), '..', 'profilegen'))
from skeletons import GOI_PROC as _GOI_PROC, IN as _IN_PROFILE  # noqa: E402

PROFILE_CONNECTOR = ('config.profile', 'a', 'G-CONFIG', 'Profile values of the tenant (sangam-config)')
SCIENTIFIC_PATH = 'procurement.scientificDepartment'


def procurement_mode(p, s, prefix):
    """Department class from the bound profile, coded refusal when not confirmed, then ifms-procurement-mode
    (outputs procurementMode, ruleRef). Returns (entry, mode rule)."""
    look = s.svc(prefix + '_deptClass', 'Department class from the bound profile (read-only)',
                 'org.sangam.packs.core.ProfileValueDelegate',
                 {'path': SCIENTIFIC_PATH, 'variable': 'scientificDepartment', 'type': 'BOOLEAN'}, async_=True)
    g = s.xgw(prefix + '_deptClassGw', 'Department class confirmed?')
    s.flow(look, g)
    unconfirmed, _ = check_conditions([('scientificDepartment', ['scientificDepartment'])])
    s.flow(g, reject_with(p, s, prefix + '_rjDeptClass', 'DEPARTMENT_CLASS_NOT_CONFIRMED'), unconfirmed,
           'not confirmed: refuse')
    mode = s.rule(prefix + '_procMode', 'Procurement mode (profile table ifms-procurement-mode)', 'ifms-procurement-mode')
    s.flow(g, mode, None, 'confirmed', default=True)
    return look, mode


def procurement_mode_table(pk):
    """The GOI ifms-procurement-mode table (profilegen skeletons.GOI_PROC, the one source), shipped as a declared pack
    placeholder: readiness blocks go-live until the tenant deploys its government's profile table, which is then the
    tenant's own table and wins over this one (Decisions.evaluate, GP-37)."""
    ccy = _IN_PROFILE['locale']['currency']
    if pk.currency != ccy:  # the bands are authored in the GOI profile currency, never assumed
        raise ValueError(f'{pk.id}: pack currency {pk.currency} differs from the GOI profile currency {ccy}')
    pk.dmn('ifms-procurement-mode', 'Procurement mode by value (pack copy of the GOI profile table)', 'FIRST',
           [('Estimated value', 'amount', 'money'), ('Scientific department', 'scientificDepartment', 'boolean')],
           [('procurementMode', 'Procurement mode', 'string'), ('ruleRef', 'Rule', 'string')],
           [([a, sci], [q(m), q(ref)], ref) for a, sci, m, ref in _GOI_PROC],
           doc='Decision 225 (IFC-189). Same rows as profiles/in/dmn/GOI/ifms-procurement-mode.dmn (status P).')
    pk.placeholder('ifms-procurement-mode', 'GOI procurement-mode bands (profilegen skeletons.GOI_PROC, status P)',
                   "deploy the government's own table from its profile (delegation.tables ifms-procurement-mode) as "
                   'the tenant table')
