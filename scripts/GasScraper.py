# Usage: Install lxml (pip install lxml) and pandas (pip install pandas) before running script
# Run with Python 3

from lxml import html
import requests
from lxml.cssselect import CSSSelector
import pandas as pd

# Convert classes to numerical values
def toValues(x):
    return {
        'p0': '0',
        'p1': '1',
        'p2': '2',
        'p3': '3',
        'p4': '4',
        'p5': '5',
        'p6': '6',
        'p7': '7',
        'p8': '8',
        'p9': '9',
        'pd': '.'
    }.get(x, "error")

# Covert a set of classes to its numerical equivalent
def convertClassesToText(classes):
    length = len(classes)
    price = ""
    for i in range(length):
        curClass = classes[i].get('class')
        price = price + toValues(curClass)
    return price

# Values stored for each gas station
columns = ["name", "address", "cross street", "phone", "cash", "87 cash", "87 cash time", "91 cash", "91 cash time", "87 credit", "87 credit time", "91 credit", "91 credit time"]
stations = pd.DataFrame(columns=columns)
pages = []

# Find the cheapest gas stations from the front page
# baseURL = 'http://losangeles.gasbuddy.com/'
# page = requests.get(baseURL)
# tree = html.fromstring(page.content)
# addresses = tree.cssselect('#pp_table>table')[0].cssselect('.address')
# for i in range(len(addresses)):
#     links = addresses[i].cssselect('a')
#     # note: if 2 links, top tier.
#     pages.append(links[len(links) - 1].get('href'))

# Use precompiled list of gas stations
pages = ["http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/8262/index.aspx",
            "http://losangeles.gasbuddy.com/Costco_Gas_Stations/Culver_City/850/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/5344/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/5769/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Hollywood/10868/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Hollywood/10939/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/6795/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Eagle_Rock/7810/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Pasadena/8666/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Altadena/934/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/South_Gate/11038/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/11167/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/6732/index.aspx",
            "http://losangeles.gasbuddy.com/Costco_Gas_Stations/Commerce/121975/index.aspx",
            "http://losangeles.gasbuddy.com/Costco_Gas_Stations/Northridge/120438/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Chatsworth/6592/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Studio_City/8486/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Vernon/10896/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/Los_Angeles/1713/index.aspx",
            "http://losangeles.gasbuddy.com/ARCO_Gas_Stations/La_Canada/7453/index.aspx"
         ]

# For each page, extract available information (if information not available, default is "None"
for i in range(len(pages)):
    # page = requests.get(baseURL + pages[i])
    page = requests.get(pages[i])
    tree = html.fromstring(page.content)
    CashCredit = tree.cssselect('li.sp_cash_credit')

    single_station = [None]*13

    single_station[0] = tree.cssselect('dl.sp_st>dt')[0].text.strip()

    address = tree.cssselect('dl.sp_st>dd')

    single_station[1] = address[0].text.strip() + address[2].text
    print(single_station[1])
    single_station[2] = address[1].text.strip()

    if(len(address) == 4):
        single_station[3] = address[3].text.strip()

    if len(CashCredit) > 0:
        single_station[4] = True
    else:
        single_station[4] = False

    if single_station[4]:
        gas87 = tree.cssselect('td.sp_A')
        if len(gas87) > 0:
            if len(gas87[0].cssselect('.sp_p')) > 0:
                single_station[5] = convertClassesToText(gas87[0].cssselect('.sp_p')[0].getchildren())
                single_station[6] = gas87[0].cssselect('div.tm')[0].text
            if len(gas87[1].cssselect('.sp_p')) > 0:
                single_station[9] = convertClassesToText(gas87[1].cssselect('.sp_p')[0].getchildren())
                single_station[10] = gas87[1].cssselect('div.tm')[0].text
        gas91 = tree.cssselect('td.sp_C')
        if len(gas91) > 0:
            if len(gas91[0].cssselect('.sp_p')) > 0:
                single_station[7] = convertClassesToText(gas91[0].cssselect('.sp_p')[0].getchildren())
                single_station[8] = gas91[0].cssselect('div.tm')[0].text
            if len(gas91[1].cssselect('.sp_p')) > 0:
                single_station[11] = convertClassesToText(gas91[1].cssselect('.sp_p')[0].getchildren())
                single_station[12] = gas91[1].cssselect('div.tm')[0].text
    else:
        gas87 = tree.cssselect('td.sp_A')
        if len(gas87) > 0:
            if len(gas87[0].cssselect('.sp_p')) > 0:
                single_station[9] = convertClassesToText(gas87[0].cssselect('.sp_p')[0].getchildren())
                single_station[10] = gas87[0].cssselect('div.tm')[0].text
        gas91 = tree.cssselect('td.sp_C')
        if len(gas91) > 0:
            if len(gas91[0].cssselect('.sp_p')) > 0:
                single_station[11] = convertClassesToText(gas91[0].cssselect('.sp_p')[0].getchildren())
                single_station[12] = gas91[0].cssselect('div.tm')[0].text


    stations.loc[len(stations)] = single_station

print(stations)

# Prints information to csv file
stations.to_json('stations.csv', orient='records')