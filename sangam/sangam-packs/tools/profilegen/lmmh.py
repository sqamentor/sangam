# SPDX-License-Identifier: Apache-2.0
"""Major heads of account (LMMH) beyond the verified sample in main.py.

The CGA LMMH PDF (correction slips to CS 1132) is not stored in the repository and was not downloaded in this pass,
so every code here carries status U: the code and title follow the LMMH coding pattern as known to the workstream and
must be checked against the official list before go-live (load the official file with SeedLoader; it supersedes these
rows by validFrom). Codes already in the verified sample (main.MAJORS) are skipped automatically."""

MAJORS_U = """
0007 Union Territory Goods and Services Tax
0009 Goods and Services Tax Compensation Cess
0022 Taxes on Agricultural Income
0023 Hotel Receipts Tax
0024 Interest Tax
0028 Other Taxes on Income and Expenditure
0031 Estate Duty
0032 Taxes on Wealth
0033 Gift Tax
0035 Taxes on Immovable Property other than Agricultural Land
0042 Taxes on Goods and Passengers
0043 Taxes and Duties on Electricity
0044 Service Tax
0045 Other Taxes and Duties on Commodities and Services
0047 Other Fiscal Services
0051 Public Service Commission
0055 Police
0056 Jails
0058 Stationery and Printing
0059 Public Works
0076 Defence Services - Army
0077 Defence Services - Navy
0078 Defence Services - Air Force
0202 Education, Sports, Art and Culture
0210 Medical and Public Health
0211 Family Welfare
0215 Water Supply and Sanitation
0216 Housing
0217 Urban Development
0220 Information and Publicity
0230 Labour, Employment and Skill Development
0235 Social Security and Welfare
0250 Other Social Services
0401 Crop Husbandry
0402 Soil and Water Conservation
0403 Animal Husbandry
0404 Dairy Development
0405 Fisheries
0406 Forestry and Wild Life
0407 Plantations
0408 Food, Storage and Warehousing
0425 Co-operation
0435 Other Agricultural Programmes
0506 Land Reforms
0515 Other Rural Development Programmes
0551 Hill Areas
0575 Other Special Areas Programmes
0700 Major Irrigation
0701 Medium Irrigation
0702 Minor Irrigation
0711 Flood Control Projects
0801 Power
0802 Petroleum
0803 Coal and Lignite
0810 New and Renewable Energy
0851 Village and Small Industries
0852 Industries
0853 Non-ferrous Mining and Metallurgical Industries
0875 Other Industries
0885 Other Receipts relating to Industries
1051 Ports and Light Houses
1052 Shipping
1053 Civil Aviation
1054 Roads and Bridges
1055 Road Transport
1056 Inland Water Transport
1425 Other Scientific Research
1452 Tourism
1456 Civil Supplies
1475 Other General Economic Services
1605 External Grant Assistance
1606 Aid Materials and Equipment
2016 Audit
2035 Collection of Other Taxes on Property and Capital Transactions
2037 Customs
2038 Union Excise Duties
2041 Taxes on Vehicles
2043 Collection Charges under Electricity Duty
2045 Other Taxes and Duties on Commodities and Services
2061 External Affairs
2407 Plantations
2416 Agricultural Financial Institutions
2700 Major Irrigation
2802 Petroleum
2803 Coal and Lignite
2853 Non-ferrous Mining and Metallurgical Industries
2875 Other Industries
2885 Other Outlays on Industries and Minerals
3052 Shipping
3056 Inland Water Transport
3075 Other Transport Services
3201 Postal Services
3275 Other Communication Services
3401 Atomic Energy Research
3402 Space Research
3403 Oceanographic Research
3425 Other Scientific Research
3435 Ecology and Environment
3456 Civil Supplies
3465 General Financial and Trading Institutions
3606 Aid Materials and Equipment
4047 Capital Outlay on Other Fiscal Services
4055 Capital Outlay on Police
4058 Capital Outlay on Stationery and Printing
4070 Capital Outlay on Other Administrative Services
4075 Capital Outlay on Miscellaneous General Services
4076 Capital Outlay on Defence Services
4211 Capital Outlay on Family Welfare
4220 Capital Outlay on Information and Publicity
4235 Capital Outlay on Social Security and Welfare
4236 Capital Outlay on Nutrition
4250 Capital Outlay on Other Social Services
4402 Capital Outlay on Soil and Water Conservation
4403 Capital Outlay on Animal Husbandry
4404 Capital Outlay on Dairy Development
4405 Capital Outlay on Fisheries
4406 Capital Outlay on Forestry and Wild Life
4407 Capital Outlay on Plantations
4408 Capital Outlay on Food, Storage and Warehousing
4415 Capital Outlay on Agricultural Research and Education
4416 Investments in Agricultural Financial Institutions
4425 Capital Outlay on Co-operation
4435 Capital Outlay on Other Agricultural Programmes
4515 Capital Outlay on Other Rural Development Programmes
4551 Capital Outlay on Hill Areas
4575 Capital Outlay on Other Special Areas Programmes
4700 Capital Outlay on Major Irrigation
4702 Capital Outlay on Minor Irrigation
4705 Capital Outlay on Command Area Development
4711 Capital Outlay on Flood Control Projects
4802 Capital Outlay on Petroleum
4803 Capital Outlay on Coal and Lignite
4810 Capital Outlay on New and Renewable Energy
4851 Capital Outlay on Village and Small Industries
4853 Capital Outlay on Non-ferrous Mining and Metallurgical Industries
4857 Capital Outlay on Chemical and Pharmaceutical Industries
4858 Capital Outlay on Engineering Industries
4859 Capital Outlay on Telecommunication and Electronic Industries
4860 Capital Outlay on Consumer Industries
4875 Capital Outlay on Other Industries
4885 Other Capital Outlay on Industries and Minerals
5051 Capital Outlay on Ports and Light Houses
5052 Capital Outlay on Shipping
5053 Capital Outlay on Civil Aviation
5055 Capital Outlay on Road Transport
5056 Capital Outlay on Inland Water Transport
5075 Capital Outlay on Other Transport Services
5201 Capital Outlay on Postal Services
5275 Capital Outlay on Other Communication Services
5402 Capital Outlay on Space Research
5425 Capital Outlay on Other Scientific and Environmental Research
5452 Capital Outlay on Tourism
5465 Investments in General Financial and Trading Institutions
5475 Capital Outlay on Other General Economic Services
6001 Internal Debt of the Central Government
6002 External Debt
6202 Loans for Education, Sports, Art and Culture
6210 Loans for Medical and Public Health
6215 Loans for Water Supply and Sanitation
6216 Loans for Housing
6217 Loans for Urban Development
6225 Loans for Welfare of SC, ST, OBC and Minorities
6235 Loans for Social Security and Welfare
6402 Loans for Soil and Water Conservation
6403 Loans for Animal Husbandry
6404 Loans for Dairy Development
6405 Loans for Fisheries
6406 Loans for Forestry and Wild Life
6407 Loans for Plantations
6408 Loans for Food, Storage and Warehousing
6425 Loans for Co-operation
6435 Loans for Other Agricultural Programmes
6515 Loans for Other Rural Development Programmes
6551 Loans for Hill Areas
6575 Loans for Other Special Areas Programmes
6701 Loans for Major and Medium Irrigation
6702 Loans for Minor Irrigation
6711 Loans for Flood Control Projects
6801 Loans for Power Projects
6851 Loans for Village and Small Industries
6853 Loans for Non-ferrous Mining and Metallurgical Industries
6857 Loans for Chemical and Pharmaceutical Industries
6858 Loans for Engineering Industries
6859 Loans for Telecommunication and Electronic Industries
6860 Loans for Consumer Industries
6875 Loans for Other Industries
6885 Other Loans to Industries and Minerals
7051 Loans for Ports and Light Houses
7053 Loans for Civil Aviation
7055 Loans for Road Transport
7056 Loans for Inland Water Transport
7075 Loans for Other Transport Services
7465 Loans for General Financial and Trading Institutions
7475 Loans for Other General Economic Services
7602 Loans and Advances to Union Territories with Legislature
7615 Miscellaneous Loans
8001 Investments of Small Savings Collections
8014 Postal Life Insurance Fund
8015 Investments of Postal Life Insurance Fund
8016 Postal Life Insurance Reserve
8115 Depreciation / Renewal Reserve Fund
8222 Sinking Funds
8223 Famine Relief Fund
8225 Roads and Bridges Fund
8226 Depreciation / Renewal Reserve Fund
8229 Development and Welfare Funds
8235 General and Other Reserve Funds
8338 Deposits of Local Funds
8550 Civil Advances
8673 Cash Balance Investment Account
8679 Accounts with Governments of Foreign Countries
8786 Adjusting Account between Central and State Governments
"""

# Minor heads: the full major-specific minor list is several thousand rows; only the standard minor heads are seeded
# (main.MINORS). This placeholder records that the remaining minors must come from the official file.
MINORS_NOTE = 'major-specific minor heads not seeded: load the official CGA LMMH file (SeedLoader CSV) [U]'


def rows(verified_codes):
    out = []
    for line in MAJORS_U.strip().splitlines():
        code, name = line.split(' ', 1)
        if code not in verified_codes:
            out.append((code, name))
    return out
