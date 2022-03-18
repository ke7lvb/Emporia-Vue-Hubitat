# Emporia Vue for Hubitat

This script requires you to add an authentication token. I am using PyEmVue to generate the token, running on a linux machine on my local network. You can pass the token into Hubitat using the native Maker API app.
https://pypi.org/project/pyemvue/

If anybody knows how to authenticate to AWS using groovy, I would be happy to embed the authentication into this script.


Start by creating a virtual device and setting the emporia driver. You then need to add your authentication token.

Once you have the token, you can retrieve the device GIDs. After that you can use Refresh to get the individual devices.

This script will create child devices for each channel you have in Emporia. 

Multiple Emporia devices under one account is supported. The power total on the parent device will be the combined total of all devices.


This is not an official app for Emporia and the API may stop working at any time.
