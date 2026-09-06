import React, { useEffect, useState } from "react";

import { getSample } from "../../services/workbench/WorkbenchService";

const SamplPage = () => {
    const [sampleStr, setSampleStr] = useState('');

    const fetchSampleStr = async () => {
        try {
            const response = await getSample();
            setSampleStr(response.data);
        } catch (error) {
            console.error(error.message);
        }
    };

    useEffect(() => {
        fetchSampleStr();
    }, []);

    return (
        <>
            <section id="home">
                <div className="container text-center">
                    <h1 className="display-4"><span style={{color: "#1F5F71;"}}>Sample Page</span></h1>
                    <p className="lead"><span style={{color: "#1F5F71;"}}>{sampleStr}</span></p>
                </div>
            </section>
        </>
    );
};

export default SamplPage;