#!/bin/bash

#
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.
#

# $1 == file
# $2 == prefix (optional)

set -e

PREFIX="$2"
# Add underscore separator if prefix is provided and doesn't already end with one
if [[ -n "${PREFIX}" && "${PREFIX}" != *_ ]]; then
	PREFIX="${PREFIX}_"
fi

while read -r line
do
	# Skip empty lines and lines starting with #
	trimmed="$(echo "$line" | sed 's/^[[:space:]]*//')"
	if [[ -z "$trimmed" || "$trimmed" == \#* ]]; then
		continue
	fi

	if [[ "$line" == *"="* ]];
	then
		key=`echo $line | cut -d \= -f 1`
		value=`echo $line | cut -d \= -f 2-`

		# Strip inline comments (# followed by space or end of line)
		# First trim trailing whitespace, then remove # and anything after it if preceded by space
		value=$(echo "$value" | sed 's/[[:space:]]*#.*//' | sed 's/[[:space:]]*$//')

		echo "$key"
		echo "$value"
		echo "${PREFIX}${key}=$value" >> $GITHUB_ENV
	fi

done < "$1"


exit $?